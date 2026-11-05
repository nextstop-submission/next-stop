package app.nextstop.core.run

import app.nextstop.core.checkpoint.CheckpointConfig
import app.nextstop.core.checkpoint.CheckpointEffect
import app.nextstop.core.checkpoint.CheckpointInput
import app.nextstop.core.checkpoint.CheckpointMachine
import app.nextstop.core.checkpoint.CheckpointPhase
import app.nextstop.core.checkpoint.CheckpointState
import app.nextstop.core.checkpoint.CompletionTrigger
import app.nextstop.core.model.Stop
import app.nextstop.core.nav.Navigator
import app.nextstop.core.time.Clock
import app.nextstop.core.vehicle.Gear
import app.nextstop.core.vehicle.VehicleEvent
import app.nextstop.core.vehicle.VehicleEventListener

enum class StopOutcome { DELIVERED, SKIPPED }

/** 배송지 1곳의 결과. 주행거리는 Odometer 값이 있을 때만 채운다. */
data class StopRecord(
    val stop: Stop,
    val outcome: StopOutcome,
    val parkedAt: Long?,
    val departedAt: Long,
    val trigger: CompletionTrigger?,
    val odometerAtDepartureKm: Double?,
    /** 직전 완료 지점(또는 출발)부터 이 배송지 출발까지 실주행거리. */
    val legKm: Double?,
) {
    val dwellMillis: Long? get() = parkedAt?.let { departedAt - it }
}

/** 화면이 그리는 데 필요한 모든 것. 불변 스냅샷으로 넘긴다. */
data class RunSnapshot(
    val stops: List<Stop>,
    val phase: CheckpointPhase,
    val gear: Gear,
    val doorOpen: Boolean,
    val records: List<StopRecord>,
    val startedAt: Long?,
    val finishedAt: Long?,
    val odometerStartKm: Double?,
    val odometerLastKm: Double?,
    val readyToComplete: Boolean,
    val sourceErrors: List<String>,
) {
    val total: Int get() = stops.size
    val delivered: Int get() = records.count { it.outcome == StopOutcome.DELIVERED }
    val done: Int get() = records.size
    val remaining: Int get() = total - done
    val currentStop: Stop?
        get() = when (val p = phase) {
            is CheckpointPhase.EnRoute -> stops.getOrNull(p.stopIndex)
            is CheckpointPhase.Parked -> stops.getOrNull(p.stopIndex)
            else -> null
        }
    val upcomingStop: Stop?
        get() = when (val p = phase) {
            is CheckpointPhase.EnRoute -> stops.getOrNull(p.stopIndex + 1)
            is CheckpointPhase.Parked -> stops.getOrNull(p.stopIndex + 1)
            else -> null
        }
    val isParked: Boolean get() = phase is CheckpointPhase.Parked
    /** 정차 중 '되돌리기'로 취소할 수 있는 마지막 기록. */
    val lastRecord: StopRecord? get() = records.lastOrNull()
    val isFinished: Boolean get() = phase is CheckpointPhase.Finished
}

/** 상태가 바뀔 때마다 스냅샷과 그 원인이 된 효과들을 함께 받는다(완료 애니메이션 트리거 등). */
fun interface RunListener {
    fun onRunChanged(snapshot: RunSnapshot, effects: List<CheckpointEffect>)
}

/**
 * 한 번의 배송 운행. 차량 신호 → 상태기계 → 내비 요청/기록을 잇는다.
 * 스레드 안전하지 않다. 호출자는 한 스레드(안드로이드 메인)에서만 호출한다.
 */
class DeliveryRun(
    plannedStops: List<Stop>,
    private val navigator: Navigator,
    private val clock: Clock,
    private val config: CheckpointConfig = CheckpointConfig(),
    /** 아직 D를 못 본 구간에서 계기판이 이만큼(km) 늘면 차가 움직인 것으로 본다([CheckpointInput.Moved]). */
    private val movedKm: Double = DEFAULT_MOVED_KM,
) : VehicleEventListener {

    private val stops = plannedStops.toMutableList()
    private var state = CheckpointState()
    private val records = mutableListOf<StopRecord>()
    private var startedAt: Long? = null
    private var finishedAt: Long? = null
    private var odometerStartKm: Double? = null
    private var odometerLastKm: Double? = null
    private var lastLegOdometerKm: Double? = null
    private val sourceErrors = ArrayDeque<String>()
    /** D를 못 본 구간의 계기판 기준값. */
    private var unarmedRefKm: Double? = null
    private val listeners = mutableListOf<RunListener>()

    /** 저장용 상태. [restore]로 같은 운행을 다시 만든다. */
    fun memento(): RunMemento = RunMemento(
        stops = stops.toList(),
        state = state,
        records = records.toList(),
        startedAt = startedAt,
        finishedAt = finishedAt,
        odometerStartKm = odometerStartKm,
        odometerLastKm = odometerLastKm,
        lastLegOdometerKm = lastLegOdometerKm,
    )

    /** 복원 직후 호출: 진행 중이던 배송지를 내비에 다시 보낸다(앱이 꺼진 사이 목적지가 사라졌을 수 있음). */
    fun resumeNavigation() {
        val i = state.currentIndex ?: return
        stops.getOrNull(i)?.let(navigator::navigateTo)
    }

    fun addListener(listener: RunListener) { listeners += listener }
    fun removeListener(listener: RunListener) { listeners -= listener }

    fun snapshot(): RunSnapshot = RunSnapshot(
        stops = stops.toList(),
        phase = state.phase,
        gear = state.lastGear,
        doorOpen = state.doorOpen,
        records = records.toList(),
        startedAt = startedAt,
        finishedAt = finishedAt,
        odometerStartKm = odometerStartKm,
        odometerLastKm = odometerLastKm,
        readyToComplete = state.readyToComplete(config, clock.nowMillis()),
        sourceErrors = sourceErrors.toList(),
    )

    fun start() {
        val now = clock.nowMillis()
        if (startedAt == null) {
            startedAt = now
            // 시작 전에 읽어 둔 값이 있으면 그것을 출발 계기판 값으로 쓴다(값이 변하지 않는 환경 대비).
            odometerLastKm?.let {
                odometerStartKm = it
                lastLegOdometerKm = it
            }
        }
        apply(CheckpointInput.Start(stops.size, now))
    }

    fun completeManually() = apply(CheckpointInput.ManualComplete(clock.nowMillis()))

    /** 부재 등: 현재 배송지를 목록 맨 뒤로 보내고 다음 배송지로 안내. 남은 곳이 자신뿐이면 무시. */
    fun deferCurrent() {
        val i = state.currentIndex ?: return
        if (i >= stops.lastIndex) return
        val stop = stops.removeAt(i)
        stops += stop
        apply(CheckpointInput.Defer(clock.nowMillis()))
    }

    fun skipCurrent() = apply(CheckpointInput.Skip(clock.nowMillis()))

    /** 방금 센 1건을 되돌린다(정산·기록에서 빠지고 그 집이 다시 현재 배송지). */
    fun undoLast() = apply(CheckpointInput.Undo(clock.nowMillis()))

    /** 정차 중 헤드유닛이 재부팅된 뒤 복원됐을 때 호출한다. */
    fun powerCycled() = apply(CheckpointInput.PowerCycled(clock.nowMillis()))

    override fun onVehicleEvent(event: VehicleEvent) {
        when (event) {
            is VehicleEvent.GearChanged -> apply(CheckpointInput.GearInput(event.gear, event.atMillis))
            is VehicleEvent.DoorChanged -> apply(CheckpointInput.DoorInput(event.open, event.atMillis))
            is VehicleEvent.OdometerUpdated -> onOdometer(event.km)
            is VehicleEvent.SourceError -> {
                sourceErrors.addLast("${event.source}: ${event.message}")
                while (sourceErrors.size > MAX_ERRORS) sourceErrors.removeFirst()
                notifyListeners(emptyList())
            }
        }
    }

    private fun onOdometer(km: Double) {
        if (!km.isFinite() || km < 0) return
        val last = odometerLastKm
        if (last != null && km < last) return // 역행 값은 버린다(센서 잡음·리셋)
        val p = state.phase
        var moved = false
        if (startedAt != null && p is CheckpointPhase.EnRoute && !p.armed) {
            // 기준값이 없으면 직전 값(복원 직후면 저장돼 있던 값)을 기준으로 삼는다.
            val ref = unarmedRefKm ?: last ?: km
            unarmedRefKm = ref
            moved = km - ref >= movedKm
        } else {
            unarmedRefKm = null
        }
        if (startedAt != null && odometerStartKm == null) {
            odometerStartKm = km
            lastLegOdometerKm = km
        }
        odometerLastKm = km
        if (moved) {
            unarmedRefKm = null
            apply(CheckpointInput.Moved(clock.nowMillis()))
        } else {
            notifyListeners(emptyList())
        }
    }

    private fun apply(input: CheckpointInput) {
        val transition = CheckpointMachine.reduce(state, input, config)
        state = transition.state
        transition.effects.forEach(::handleEffect)
        notifyListeners(transition.effects)
    }

    private fun handleEffect(effect: CheckpointEffect) {
        when (effect) {
            is CheckpointEffect.NavigateTo -> stops.getOrNull(effect.stopIndex)?.let(navigator::navigateTo)
            is CheckpointEffect.StopCompleted -> record(
                stops[effect.stopIndex], StopOutcome.DELIVERED, effect.parkedAt, effect.departedAt, effect.trigger,
            )
            is CheckpointEffect.StopSkipped -> record(
                stops[effect.stopIndex], StopOutcome.SKIPPED, null, effect.atMillis, null,
            )
            is CheckpointEffect.AllDone -> {
                finishedAt = effect.atMillis
                navigator.cancel()
            }
            is CheckpointEffect.StopUndone -> {
                records.removeLastOrNull()
                lastLegOdometerKm = records.lastOrNull()?.odometerAtDepartureKm ?: odometerStartKm
                finishedAt = null
            }
            is CheckpointEffect.DoorCycleCredited,
            is CheckpointEffect.Parked,
            is CheckpointEffect.StopDeferred,
            is CheckpointEffect.Ignored -> Unit
        }
    }

    private fun record(stop: Stop, outcome: StopOutcome, parkedAt: Long?, at: Long, trigger: CompletionTrigger?) {
        val odo = odometerLastKm
        val leg = if (odo != null && lastLegOdometerKm != null) odo - lastLegOdometerKm!! else null
        if (odo != null) lastLegOdometerKm = odo
        records += StopRecord(stop, outcome, parkedAt, at, trigger, odo, leg)
    }

    private fun notifyListeners(effects: List<CheckpointEffect>) {
        val snap = snapshot()
        listeners.toList().forEach { it.onRunChanged(snap, effects) }
    }

    companion object {
        private const val MAX_ERRORS = 5
        const val DEFAULT_MOVED_KM = 0.5

        fun restore(m: RunMemento, navigator: Navigator, clock: Clock, config: CheckpointConfig = CheckpointConfig()): DeliveryRun =
            DeliveryRun(m.stops, navigator, clock, config).also { r ->
                r.state = m.state
                r.records += m.records
                r.startedAt = m.startedAt
                r.finishedAt = m.finishedAt
                r.odometerStartKm = m.odometerStartKm
                r.odometerLastKm = m.odometerLastKm
                r.lastLegOdometerKm = m.lastLegOdometerKm
            }
    }
}
