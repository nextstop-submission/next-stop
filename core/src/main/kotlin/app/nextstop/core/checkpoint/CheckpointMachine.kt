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
 * - 주행 중(P 아님) 도어 이벤트는 무시한다.
 * - 운행 시작 직후·수동 완료 직후처럼 아직 D를 넣지 않은 구간의 P·도어는 판정하지 않는다(출발지 상차 오탐 방지).
 * - P에서 R/N은 주차 상태 유지(후진 출차 후 D에서 완료).
 *
 * 순수 함수 [reduce]만 노출해 단위 테스트로 모든 전이를 검증한다.
 */
data class CheckpointConfig(
    val minParkedMillis: Long = 0L,
    val requireDoorCycle: Boolean = true,
)

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
}

enum class CompletionTrigger { GEAR_DRIVE, MANUAL }

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
}

data class Transition(val state: CheckpointState, val effects: List<CheckpointEffect>)

object CheckpointMachine {

    fun reduce(state: CheckpointState, input: CheckpointInput, config: CheckpointConfig = CheckpointConfig()): Transition {
        if (input is CheckpointInput.Start) return start(state, input)

        val phase = state.phase
        if (phase is CheckpointPhase.Idle) return ignore(trackSignals(state, input), IgnoreReason.NOT_STARTED)
        if (phase is CheckpointPhase.Finished) return ignore(trackSignals(state, input), IgnoreReason.ALREADY_FINISHED)

        return when (input) {
            is CheckpointInput.GearInput -> onGear(state, input, config)
            is CheckpointInput.DoorInput -> onDoor(state, input)
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
                    Transition(
                        tracked.copy(
                            phase = CheckpointPhase.Parked(
                                stopIndex = phase.stopIndex,
                                parkedAt = input.atMillis,
                                doorOpen = state.doorOpen,
                            ),
                        ),
                        listOf(CheckpointEffect.Parked(phase.stopIndex, input.atMillis)),
                    )
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
            // 배송이 아니었던 정차. 같은 배송지로 계속 주행.
            return Transition(
                state.copy(phase = CheckpointPhase.EnRoute(parked.stopIndex, armed = true)),
                listOf(CheckpointEffect.Ignored(reason)),
            )
        }
        return complete(state, at, CompletionTrigger.GEAR_DRIVE)
    }

    private fun onDoor(state: CheckpointState, input: CheckpointInput.DoorInput): Transition {
        if (input.open == state.doorOpen) return ignore(state, IgnoreReason.DOOR_UNCHANGED)
        val tracked = state.copy(doorOpen = input.open)
        val parked = state.phase as? CheckpointPhase.Parked
            ?: return Transition(tracked, listOf(CheckpointEffect.Ignored(IgnoreReason.DOOR_WHILE_MOVING)))

        val next = if (input.open) {
            parked.copy(doorOpen = true, firstDoorOpenAt = parked.firstDoorOpenAt ?: input.atMillis)
        } else {
            parked.copy(doorOpen = false, doorCycles = if (parked.doorOpen) parked.doorCycles + 1 else parked.doorCycles)
        }
        return Transition(tracked.copy(phase = next), emptyList())
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
        return advance(state, i, at, listOf(effect))
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
