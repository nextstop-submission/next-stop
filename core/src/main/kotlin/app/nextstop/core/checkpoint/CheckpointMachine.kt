package app.nextstop.core.checkpoint

import app.nextstop.core.vehicle.Gear

/**
 * '1건 완료' 판정 상태기계. 위치 신호 없이 차량 신호만으로 동작한다.
 *
 *   EnRoute(i) --기어 D(무장)--> --기어 P--> Parked(i) --도어 열림→닫힘(1회 이상)--> Parked(i, 준비됨) --기어 D--> 완료 → EnRoute(i+1)
 *
 * 오탐 방지
 * - 주차 중 도어 사이클이 없으면 D로 출발해도 완료로 치지 않는다(신호 대기·잠깐 정차).
 * - [CheckpointConfig.minParkedMillis]보다 짧게 서 있었으면 완료로 치지 않는다.
 * - 주행 중(P 아님) 도어 이벤트는 그 자체로는 완료가 아니다. 다만 신호 순서 역전을 보정하려고 잠시 들고 있는다(아래).
 * - 운행 시작 직후·수동 완료 직후처럼 아직 D를 넣지 않은 구간의 P·도어는 판정하지 않는다(출발지 상차 오탐 방지).
 * - P에서 R/N은 주차 상태 유지(후진 출차 후 D에서 완료).
 *
 * 신호 순서 역전 보정(문 = Pleos Vehicle Service, 기어 = AAOS VHAL — 경로가 달라 도착 순서가 바뀔 수 있고,
 * 문 콜백에는 발생 시각이 없어 받은 시각으로 찍힌다)
 * - P보다 먼저 받은 문 열림→닫힘이 [CheckpointConfig.doorBeforeParkMillis] 안이면 그 정차의 문 사이클로 인정한다.
 * - 문 사이클 없이(또는 문이 열린 채) D로 떠난 뒤 [CheckpointConfig.doorAfterDepartMillis] 안에 문 닫힘이 오면 방금 정차를 완료로 되살린다.
 * - 아직 D를 못 본 구간에서 계기판이 늘면([CheckpointInput.Moved]) 놓친 D(와 P)를 보정한다(앱 재시작 중 유실 대비).
 * - 정차 중 헤드유닛이 재부팅됐으면([CheckpointInput.PowerCycled]) 문 사이클 1회로 본다(꺼진 동안 문 이벤트를 받을 수 없음).
 *
 * 오탐 되돌리기: [CheckpointInput.Undo]는 방금 센 1건을 취소하고 그 집을 다시 현재 배송지로 만든다.
 *
 * 순수 함수 [reduce]만 노출해 단위 테스트로 모든 전이를 검증한다.
 */
data class CheckpointConfig(
    val minParkedMillis: Long = 0L,
    val requireDoorCycle: Boolean = true,
    /**
     * P보다 먼저 받은 문 사이클을 인정하는 창(보정 손잡이, 0이면 끔). 기본 60초: 11/2 CI 리허설에서 같은 Virtual Console 조작의
     * 앱 수신이 단계마다 수십 초씩 흔들렸다. 실제 주행 중에는 문이 열리지 않으므로 창이 길어도 오탐 위험이 작다.
     */
    val doorBeforeParkMillis: Long = DEFAULT_DOOR_BEFORE_PARK_MILLIS,
    /**
     * 떠난 뒤 늦게 받은 문 닫힘을 방금 정차로 인정하는 창(보정 손잡이, 0이면 끔). 기본 15초: 다음 집의 문 신호를 앞 정차로
     * 잘못 붙이지 않도록 짧게 둔다(배송지 사이 주행은 보통 수 분, CI 리허설은 D 뒤 문 닫힘까지 약 24초).
     */
    val doorAfterDepartMillis: Long = DEFAULT_DOOR_AFTER_DEPART_MILLIS,
) {
    companion object {
        const val DEFAULT_DOOR_BEFORE_PARK_MILLIS = 60_000L
        const val DEFAULT_DOOR_AFTER_DEPART_MILLIS = 15_000L
    }
}

/** 문 사이클 없이(또는 문이 열린 채) 떠난 직전 정차. 늦게 온 문 닫힘으로 완료를 되살릴 때 쓴다. */
data class MissedStop(val stopIndex: Int, val parkedAt: Long, val departedAt: Long, val doorOpenAtDeparture: Boolean)

sealed interface CheckpointPhase {
    data object Idle : CheckpointPhase

    /**
     * 배송지 [stopIndex]로 가는 중.
     * [armed]=false면 아직 이 구간에서 D를 넣은 적이 없다(출발지에서 짐 싣는 중 등). 이때의 P·도어는 판정하지 않는다.
     */
    data class EnRoute(val stopIndex: Int, val armed: Boolean = true) : CheckpointPhase

    data class Parked(
        val stopIndex: Int,
        val parkedAt: Long,
        val doorOpen: Boolean = false,
        val doorCycles: Int = 0,
        val firstDoorOpenAt: Long? = null,
    ) : CheckpointPhase

    data object Finished : CheckpointPhase
}

data class CheckpointState(
    val phase: CheckpointPhase = CheckpointPhase.Idle,
    val totalStops: Int = 0,
    val lastGear: Gear = Gear.UNKNOWN,
    val doorOpen: Boolean = false,
    /** 주행 중(P 아님) 받은 마지막 문 열림·닫힘 시각. P가 늦게 온 경우를 위해 잠시 들고 있는다(저장하지 않음). */
    val looseDoorOpenAt: Long? = null,
    val looseDoorClosedAt: Long? = null,
    val lastMissed: MissedStop? = null,
) {
    val currentIndex: Int?
        get() = when (val p = phase) {
            is CheckpointPhase.EnRoute -> p.stopIndex
            is CheckpointPhase.Parked -> p.stopIndex
            else -> null
        }

    /** 지금 D를 넣으면 완료로 인정되는가(화면에 '출발하면 완료' 힌트를 띄우는 데 쓴다). */
    fun readyToComplete(config: CheckpointConfig, now: Long): Boolean {
        val p = phase as? CheckpointPhase.Parked ?: return false
        val doorOk = !config.requireDoorCycle || p.doorCycles > 0
        return doorOk && !p.doorOpen && now - p.parkedAt >= config.minParkedMillis
    }
}

sealed interface CheckpointInput {
    val atMillis: Long

    data class Start(val totalStops: Int, override val atMillis: Long) : CheckpointInput
    data class GearInput(val gear: Gear, override val atMillis: Long) : CheckpointInput
    data class DoorInput(val open: Boolean, override val atMillis: Long) : CheckpointInput

    /** 정차 중 사용자가 직접 '완료'를 누름(신호 누락 대비). */
    data class ManualComplete(override val atMillis: Long) : CheckpointInput

    /** 부재 등으로 이번 배송지를 맨 뒤로 미룸. 실제 순서 변경은 상위(DeliveryRun)가 한다. */
    data class Defer(override val atMillis: Long) : CheckpointInput

    /** 이번 배송지를 건너뜀(취소·반송). */
    data class Skip(override val atMillis: Long) : CheckpointInput

    /** 방금 센 1건(완료·건너뜀)을 되돌린다. 그 집이 다시 현재 배송지가 된다(정차 중 1탭, 편의점·부재 오탐 대비). */
    data class Undo(override val atMillis: Long) : CheckpointInput

    /** 아직 D를 못 본 구간에서 차가 움직였다는 증거(계기판 증가). 앱이 재시작되는 사이 놓친 D를 대신한다. */
    data class Moved(override val atMillis: Long) : CheckpointInput

    /** 정차 중 헤드유닛이 재부팅됨(시동 끔→켬). 꺼진 동안 문 이벤트를 받을 수 없으므로 문 사이클 1회로 본다. */
    data class PowerCycled(override val atMillis: Long) : CheckpointInput
}

enum class CompletionTrigger { GEAR_DRIVE, MANUAL }

/** 문 사이클을 직접 보지 못했지만 인정한 이유(로그·실측 분석용). */
enum class CreditReason { DOOR_BEFORE_PARK, DOOR_AFTER_DEPARTURE, POWER_CYCLE }

sealed interface CheckpointEffect {
    data class NavigateTo(val stopIndex: Int) : CheckpointEffect

    data class Parked(val stopIndex: Int, val atMillis: Long) : CheckpointEffect

    data class StopCompleted(
        val stopIndex: Int,
        val parkedAt: Long?,
        val departedAt: Long,
        val doorCycles: Int,
        val trigger: CompletionTrigger,
    ) : CheckpointEffect

    data class StopDeferred(val stopIndex: Int, val atMillis: Long) : CheckpointEffect
    data class StopSkipped(val stopIndex: Int, val atMillis: Long) : CheckpointEffect

    data class AllDone(val atMillis: Long) : CheckpointEffect

    data class DoorCycleCredited(val stopIndex: Int, val reason: CreditReason) : CheckpointEffect

    /** [stopIndex]의 기록을 지우고 그 집을 다시 현재 배송지로 만든다. */
    data class StopUndone(val stopIndex: Int, val atMillis: Long) : CheckpointEffect

    /** 상태를 바꾸지 않은 입력과 그 이유(로그·실측 분석용). */
    data class Ignored(val reason: IgnoreReason) : CheckpointEffect
}

enum class IgnoreReason {
    NOT_STARTED,
    ALREADY_FINISHED,
    ALREADY_STARTED,
    DOOR_WHILE_MOVING,
    DEPARTED_WITHOUT_DOOR_CYCLE,
    DEPARTED_TOO_SOON,
    DEPARTED_WITH_DOOR_OPEN,
    REPEATED_GEAR,
    DOOR_UNCHANGED,
    NOTHING_TO_UNDO,
    NOT_PARKED,
    ALREADY_ARMED,
}

data class Transition(val state: CheckpointState, val effects: List<CheckpointEffect>)

object CheckpointMachine {

    fun reduce(state: CheckpointState, input: CheckpointInput, config: CheckpointConfig = CheckpointConfig()): Transition {
        if (input is CheckpointInput.Start) return start(state, input)

        val phase = state.phase
        if (phase is CheckpointPhase.Idle) return ignore(trackSignals(state, input), IgnoreReason.NOT_STARTED)
        if (phase is CheckpointPhase.Finished) {
            return if (input is CheckpointInput.Undo) undo(state, input.atMillis)
            else ignore(trackSignals(state, input), IgnoreReason.ALREADY_FINISHED)
        }

        return when (input) {
            is CheckpointInput.GearInput -> onGear(state, input, config)
            is CheckpointInput.DoorInput -> onDoor(state, input, config)
            is CheckpointInput.Undo -> undo(state, input.atMillis)
            is CheckpointInput.Moved -> onMoved(state, input.atMillis)
            is CheckpointInput.PowerCycled -> onPowerCycled(state)
            is CheckpointInput.ManualComplete -> complete(state, input.atMillis, CompletionTrigger.MANUAL)
            is CheckpointInput.Defer -> {
                // 미룬 배송지는 상위에서 목록 끝으로 이동하므로 같은 인덱스가 다음 배송지를 가리킨다.
                val i = state.currentIndex!!
                Transition(
                    state.copy(phase = CheckpointPhase.EnRoute(i, armed = state.lastGear == Gear.DRIVE)),
                    listOf(CheckpointEffect.StopDeferred(i, input.atMillis), CheckpointEffect.NavigateTo(i)),
                )
            }
            is CheckpointInput.Skip -> {
                val i = state.currentIndex!!
                advance(state, i, input.atMillis, listOf(CheckpointEffect.StopSkipped(i, input.atMillis)))
            }
            is CheckpointInput.Start -> error("handled above")
        }
    }

    private fun start(state: CheckpointState, input: CheckpointInput.Start): Transition {
        if (state.phase !is CheckpointPhase.Idle) return ignore(state, IgnoreReason.ALREADY_STARTED)
        require(input.totalStops >= 0)
        if (input.totalStops == 0) {
            return Transition(
                state.copy(phase = CheckpointPhase.Finished, totalStops = 0),
                listOf(CheckpointEffect.AllDone(input.atMillis)),
            )
        }
        return Transition(
            state.copy(
                phase = CheckpointPhase.EnRoute(0, armed = state.lastGear == Gear.DRIVE),
                totalStops = input.totalStops,
            ),
            listOf(CheckpointEffect.NavigateTo(0)),
        )
    }

    private fun onGear(state: CheckpointState, input: CheckpointInput.GearInput, config: CheckpointConfig): Transition {
        val gear = input.gear
        if (gear == state.lastGear) return ignore(state, IgnoreReason.REPEATED_GEAR)
        val tracked = state.copy(lastGear = gear)

        return when (val phase = state.phase) {
            is CheckpointPhase.EnRoute -> {
                if (!phase.armed) {
                    val armed = if (gear == Gear.DRIVE) phase.copy(armed = true) else phase
                    Transition(tracked.copy(phase = armed), emptyList())
                } else if (gear == Gear.PARK) {
                    park(tracked, phase.stopIndex, input.atMillis, config)
                } else {
                    Transition(tracked, emptyList())
                }
            }
            is CheckpointPhase.Parked -> when (gear) {
                Gear.DRIVE -> departFromParked(tracked, phase, input.atMillis, config)
                // R/N/P/UNKNOWN: 아직 주차장 안에서 움직이는 중으로 본다.
                else -> Transition(tracked, emptyList())
            }
            else -> Transition(tracked, emptyList())
        }
    }

    private fun departFromParked(
        state: CheckpointState,
        parked: CheckpointPhase.Parked,
        at: Long,
        config: CheckpointConfig,
    ): Transition {
        val reason = when {
            config.requireDoorCycle && parked.doorCycles == 0 -> IgnoreReason.DEPARTED_WITHOUT_DOOR_CYCLE
            parked.doorOpen -> IgnoreReason.DEPARTED_WITH_DOOR_OPEN
            at - parked.parkedAt < config.minParkedMillis -> IgnoreReason.DEPARTED_TOO_SOON
            else -> null
        }
        if (reason != null) {
            // 배송이 아니었던 정차(로 보임). 같은 배송지로 계속 주행. 늦게 올 문 신호를 위해 정차를 기억해 둔다.
            val missed = if (reason == IgnoreReason.DEPARTED_TOO_SOON) null
            else MissedStop(parked.stopIndex, parked.parkedAt, at, parked.doorOpen)
            return Transition(
                state.copy(phase = CheckpointPhase.EnRoute(parked.stopIndex, armed = true), lastMissed = missed),
                listOf(CheckpointEffect.Ignored(reason)),
            )
        }
        return complete(state, at, CompletionTrigger.GEAR_DRIVE)
    }

    private fun park(state: CheckpointState, i: Int, at: Long, config: CheckpointConfig): Transition {
        val open = state.looseDoorOpenAt?.takeIf { config.doorBeforeParkMillis > 0 && at - it <= config.doorBeforeParkMillis }
        val closed = state.looseDoorClosedAt
        // P가 늦게 왔다: 창 안에서 먼저 받은 문 열림→닫힘을 이번 정차의 사이클로 친다.
        val early = open != null && closed != null && closed >= open
        val parked = CheckpointPhase.Parked(
            stopIndex = i,
            parkedAt = at,
            doorOpen = state.doorOpen,
            doorCycles = if (early) 1 else 0,
            firstDoorOpenAt = open,
        )
        val effects = mutableListOf<CheckpointEffect>(CheckpointEffect.Parked(i, at))
        if (early) effects += CheckpointEffect.DoorCycleCredited(i, CreditReason.DOOR_BEFORE_PARK)
        return Transition(state.copy(phase = parked, looseDoorOpenAt = null, looseDoorClosedAt = null, lastMissed = null), effects)
    }

    private fun onDoor(state: CheckpointState, input: CheckpointInput.DoorInput, config: CheckpointConfig): Transition {
        if (input.open == state.doorOpen) return ignore(state, IgnoreReason.DOOR_UNCHANGED)
        val tracked = state.copy(doorOpen = input.open)
        val parked = state.phase as? CheckpointPhase.Parked
        if (parked == null) {
            val route = state.phase as CheckpointPhase.EnRoute
            // 출발지 상차(아직 D 전)의 문은 판정하지 않는다.
            if (!route.armed) return Transition(tracked, listOf(CheckpointEffect.Ignored(IgnoreReason.DOOR_WHILE_MOVING)))
            if (input.open) return Transition(tracked.copy(looseDoorOpenAt = input.atMillis, looseDoorClosedAt = null), emptyList())
            val missed = state.lastMissed
            val sawOpen = missed != null &&
                (missed.doorOpenAtDeparture || (state.looseDoorOpenAt ?: Long.MIN_VALUE) >= missed.departedAt)
            if (missed != null && sawOpen && missed.stopIndex == route.stopIndex && config.doorAfterDepartMillis > 0 &&
                input.atMillis - missed.departedAt <= config.doorAfterDepartMillis
            ) {
                // D가 문 신호보다 먼저 왔다: 방금 떠난 정차를 완료로 되살린다.
                val cleared = tracked.copy(looseDoorOpenAt = null, looseDoorClosedAt = null, lastMissed = null)
                val effects = listOf(
                    CheckpointEffect.DoorCycleCredited(missed.stopIndex, CreditReason.DOOR_AFTER_DEPARTURE),
                    CheckpointEffect.StopCompleted(missed.stopIndex, missed.parkedAt, missed.departedAt, 1, CompletionTrigger.GEAR_DRIVE),
                )
                return advance(cleared, missed.stopIndex, input.atMillis, effects)
            }
            return Transition(tracked.copy(looseDoorClosedAt = input.atMillis), emptyList())
        }

        val next = if (input.open) {
            parked.copy(doorOpen = true, firstDoorOpenAt = parked.firstDoorOpenAt ?: input.atMillis)
        } else {
            parked.copy(doorOpen = false, doorCycles = if (parked.doorOpen) parked.doorCycles + 1 else parked.doorCycles)
        }
        return Transition(tracked.copy(phase = next), emptyList())
    }

    private fun onMoved(state: CheckpointState, at: Long): Transition {
        val route = state.phase as? CheckpointPhase.EnRoute ?: return ignore(state, IgnoreReason.NOT_PARKED)
        if (route.armed) return ignore(state, IgnoreReason.ALREADY_ARMED)
        // 움직였는데 지금 P다 = D와 P를 둘 다 놓쳤다. 지금부터 이 집에 정차한 것으로 본다.
        return if (state.lastGear == Gear.PARK) {
            Transition(
                state.copy(phase = CheckpointPhase.Parked(route.stopIndex, at, doorOpen = state.doorOpen)),
                listOf(CheckpointEffect.Parked(route.stopIndex, at)),
            )
        } else {
            Transition(state.copy(phase = route.copy(armed = true)), emptyList())
        }
    }

    private fun onPowerCycled(state: CheckpointState): Transition {
        val p = state.phase as? CheckpointPhase.Parked ?: return ignore(state, IgnoreReason.NOT_PARKED)
        if (p.doorCycles > 0) return Transition(state, emptyList())
        return Transition(
            state.copy(phase = p.copy(doorCycles = 1)),
            listOf(CheckpointEffect.DoorCycleCredited(p.stopIndex, CreditReason.POWER_CYCLE)),
        )
    }

    private fun undo(state: CheckpointState, at: Long): Transition {
        val current = when (val p = state.phase) {
            is CheckpointPhase.EnRoute -> p.stopIndex
            is CheckpointPhase.Parked -> p.stopIndex
            CheckpointPhase.Finished -> state.totalStops
            CheckpointPhase.Idle -> 0
        }
        if (current <= 0) return ignore(state, IgnoreReason.NOTHING_TO_UNDO)
        val back = current - 1
        val phase = when (val p = state.phase) {
            is CheckpointPhase.EnRoute -> p.copy(stopIndex = back)
            is CheckpointPhase.Parked -> p.copy(stopIndex = back)
            else -> if (state.lastGear == Gear.PARK) CheckpointPhase.Parked(back, at, doorOpen = state.doorOpen)
            else CheckpointPhase.EnRoute(back, armed = state.lastGear == Gear.DRIVE)
        }
        return Transition(
            state.copy(phase = phase, lastMissed = null),
            listOf(CheckpointEffect.StopUndone(back, at), CheckpointEffect.NavigateTo(back)),
        )
    }

    private fun complete(state: CheckpointState, at: Long, trigger: CompletionTrigger): Transition {
        val i = state.currentIndex!!
        val parked = state.phase as? CheckpointPhase.Parked
        val effect = CheckpointEffect.StopCompleted(
            stopIndex = i,
            parkedAt = parked?.parkedAt,
            departedAt = at,
            doorCycles = parked?.doorCycles ?: 0,
            trigger = trigger,
        )
        return advance(state.copy(looseDoorOpenAt = null, looseDoorClosedAt = null, lastMissed = null), i, at, listOf(effect))
    }

    private fun advance(state: CheckpointState, i: Int, at: Long, effects: List<CheckpointEffect>): Transition {
        val next = i + 1
        return if (next < state.totalStops) {
            // 수동 완료·건너뛰기를 P 상태에서 누른 경우, 다음 구간은 D를 넣기 전까지 판정하지 않는다.
            val armed = state.lastGear == Gear.DRIVE
            Transition(state.copy(phase = CheckpointPhase.EnRoute(next, armed)), effects + CheckpointEffect.NavigateTo(next))
        } else {
            Transition(state.copy(phase = CheckpointPhase.Finished), effects + CheckpointEffect.AllDone(at))
        }
    }

    private fun trackSignals(state: CheckpointState, input: CheckpointInput): CheckpointState = when (input) {
        is CheckpointInput.GearInput -> state.copy(lastGear = input.gear)
        is CheckpointInput.DoorInput -> state.copy(doorOpen = input.open)
        else -> state
    }

    private fun ignore(state: CheckpointState, reason: IgnoreReason) =
        Transition(state, listOf(CheckpointEffect.Ignored(reason)))
}
