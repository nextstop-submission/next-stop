package app.nextstop.core

import app.nextstop.core.checkpoint.CheckpointConfig
import app.nextstop.core.checkpoint.CheckpointEffect
import app.nextstop.core.checkpoint.CheckpointInput
import app.nextstop.core.checkpoint.CheckpointInput.DoorInput
import app.nextstop.core.checkpoint.CheckpointInput.GearInput
import app.nextstop.core.checkpoint.CheckpointMachine
import app.nextstop.core.checkpoint.CheckpointPhase
import app.nextstop.core.checkpoint.CheckpointState
import app.nextstop.core.checkpoint.CompletionTrigger
import app.nextstop.core.checkpoint.IgnoreReason
import app.nextstop.core.vehicle.Gear
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CheckpointMachineTest {

    private class Driver(total: Int, private val config: CheckpointConfig = CheckpointConfig()) {
        var state = CheckpointState()
        val effects = mutableListOf<CheckpointEffect>()
        var t = 0L

        init {
            send(CheckpointInput.Start(total, t))
        }

        fun send(input: CheckpointInput): List<CheckpointEffect> {
            val tr = CheckpointMachine.reduce(state, input, config)
            state = tr.state
            effects += tr.effects
            return tr.effects
        }

        fun gear(g: Gear, dt: Long = 1_000) = send(GearInput(g, tick(dt)))
        fun door(open: Boolean, dt: Long = 1_000) = send(DoorInput(open, tick(dt)))
        private fun tick(dt: Long): Long { t += dt; return t }

        /** 정상 배송 1건: P → 열림 → 닫힘 → D */
        fun deliverOne(): List<CheckpointEffect> {
            gear(Gear.PARK)
            door(true)
            door(false)
            return gear(Gear.DRIVE)
        }

        fun completed() = effects.filterIsInstance<CheckpointEffect.StopCompleted>()
    }

    @Test
    fun `start navigates to first stop`() {
        val d = Driver(3)
        assertEquals(listOf<CheckpointEffect>(CheckpointEffect.NavigateTo(0)), d.effects)
        assertEquals(CheckpointPhase.EnRoute(0, armed = false), d.state.phase)
    }

    @Test
    fun `zero stops finishes immediately`() {
        val d = Driver(0)
        assertIs<CheckpointPhase.Finished>(d.state.phase)
        assertIs<CheckpointEffect.AllDone>(d.effects.single())
    }

    @Test
    fun `park door cycle drive completes a stop and navigates to next`() {
        val d = Driver(3)
        d.gear(Gear.DRIVE)
        val out = d.deliverOne()
        val done = out.filterIsInstance<CheckpointEffect.StopCompleted>().single()
        assertEquals(0, done.stopIndex)
        assertEquals(1, done.doorCycles)
        assertEquals(CompletionTrigger.GEAR_DRIVE, done.trigger)
        assertTrue(CheckpointEffect.NavigateTo(1) in out)
        assertEquals(CheckpointPhase.EnRoute(1, armed = true), d.state.phase)
    }

    @Test
    fun `whole route finishes with AllDone after last stop`() {
        val d = Driver(2)
        d.gear(Gear.DRIVE)
        d.deliverOne()
        val out = d.deliverOne()
        assertIs<CheckpointEffect.AllDone>(out.last())
        assertIs<CheckpointPhase.Finished>(d.state.phase)
        assertEquals(listOf(0, 1), d.completed().map { it.stopIndex })
    }

    @Test
    fun `loading at depot before first drive is not a delivery`() {
        val d = Driver(2)
        // 출발지에서 P 상태로 짐 싣기: 문 여닫고 D
        d.gear(Gear.PARK)
        d.door(true)
        d.door(false)
        d.gear(Gear.DRIVE)
        assertTrue(d.completed().isEmpty())
        assertEquals(CheckpointPhase.EnRoute(0, armed = true), d.state.phase)
    }

    @Test
    fun `parking without door cycle is ignored as traffic stop`() {
        val d = Driver(2)
        d.gear(Gear.DRIVE)
        d.gear(Gear.PARK)
        val out = d.gear(Gear.DRIVE)
        assertEquals(listOf<CheckpointEffect>(CheckpointEffect.Ignored(IgnoreReason.DEPARTED_WITHOUT_DOOR_CYCLE)), out)
        assertEquals(CheckpointPhase.EnRoute(0, armed = true), d.state.phase)
    }

    @Test
    fun `door events while driving are ignored`() {
        val d = Driver(2)
        d.gear(Gear.DRIVE)
        assertEquals(listOf<CheckpointEffect>(CheckpointEffect.Ignored(IgnoreReason.DOOR_WHILE_MOVING)), d.door(true))
        d.door(false)
        d.gear(Gear.PARK)
        // 주행 중 사이클은 이번 정차의 사이클로 치지 않는다
        val out = d.gear(Gear.DRIVE)
        assertTrue(CheckpointEffect.Ignored(IgnoreReason.DEPARTED_WITHOUT_DOOR_CYCLE) in out)
    }

    @Test
    fun `departing with door still open does not complete`() {
        val d = Driver(2)
        d.gear(Gear.DRIVE)
        d.gear(Gear.PARK)
        d.door(true)
        d.door(false)
        d.door(true)
        val out = d.gear(Gear.DRIVE)
        assertTrue(CheckpointEffect.Ignored(IgnoreReason.DEPARTED_WITH_DOOR_OPEN) in out)
    }

    @Test
    fun `reverse out of parking then drive completes`() {
        val d = Driver(2)
        d.gear(Gear.DRIVE)
        d.gear(Gear.PARK)
        d.door(true)
        d.door(false)
        assertTrue(d.gear(Gear.REVERSE).isEmpty())
        assertIs<CheckpointPhase.Parked>(d.state.phase)
        val out = d.gear(Gear.DRIVE)
        assertEquals(1, out.filterIsInstance<CheckpointEffect.StopCompleted>().size)
    }

    @Test
    fun `multiple door cycles count once per stop`() {
        val d = Driver(1)
        d.gear(Gear.DRIVE)
        d.gear(Gear.PARK)
        repeat(3) { d.door(true); d.door(false) }
        val done = d.gear(Gear.DRIVE).filterIsInstance<CheckpointEffect.StopCompleted>().single()
        assertEquals(3, done.doorCycles)
    }

    @Test
    fun `minimum parked time guards against too short stops`() {
        val d = Driver(2, CheckpointConfig(minParkedMillis = 20_000))
        d.gear(Gear.DRIVE)
        d.gear(Gear.PARK)
        d.door(true, dt = 2_000)
        d.door(false, dt = 2_000)
        val out = d.gear(Gear.DRIVE, dt = 2_000)
        assertTrue(CheckpointEffect.Ignored(IgnoreReason.DEPARTED_TOO_SOON) in out)

        d.gear(Gear.PARK)
        d.door(true, dt = 10_000)
        d.door(false, dt = 10_000)
        val ok = d.gear(Gear.DRIVE, dt = 1_000)
        assertEquals(1, ok.filterIsInstance<CheckpointEffect.StopCompleted>().size)
    }

    @Test
    fun `repeated identical gear and door events are no-ops`() {
        val d = Driver(1)
        d.gear(Gear.DRIVE)
        assertEquals(listOf<CheckpointEffect>(CheckpointEffect.Ignored(IgnoreReason.REPEATED_GEAR)), d.gear(Gear.DRIVE))
        d.gear(Gear.PARK)
        d.door(true)
        assertEquals(listOf<CheckpointEffect>(CheckpointEffect.Ignored(IgnoreReason.DOOR_UNCHANGED)), d.door(true))
    }

    @Test
    fun `manual complete while parked advances and disarms until next drive`() {
        val d = Driver(3)
        d.gear(Gear.DRIVE)
        d.gear(Gear.PARK)
        val out = d.send(CheckpointInput.ManualComplete(d.t + 1))
        assertEquals(CompletionTrigger.MANUAL, out.filterIsInstance<CheckpointEffect.StopCompleted>().single().trigger)
        assertEquals(CheckpointPhase.EnRoute(1, armed = false), d.state.phase)
        // 같은 자리에서 문을 여닫아도 다음 배송지 완료로 잡히지 않는다
        d.door(true)
        d.door(false)
        d.gear(Gear.DRIVE)
        assertEquals(1, d.completed().size)
    }

    @Test
    fun `skip advances without completion`() {
        val d = Driver(2)
        val out = d.send(CheckpointInput.Skip(1))
        assertTrue(CheckpointEffect.StopSkipped(0, 1) in out)
        assertTrue(CheckpointEffect.NavigateTo(1) in out)
        assertTrue(d.completed().isEmpty())
    }

    @Test
    fun `defer keeps index and renavigates`() {
        val d = Driver(3)
        d.gear(Gear.DRIVE)
        val out = d.send(CheckpointInput.Defer(d.t + 1))
        assertEquals(listOf(CheckpointEffect.StopDeferred(0, d.t + 1), CheckpointEffect.NavigateTo(0)), out)
        assertEquals(CheckpointPhase.EnRoute(0, armed = true), d.state.phase)
    }

    @Test
    fun `inputs before start and after finish are ignored`() {
        val idle = CheckpointMachine.reduce(CheckpointState(), GearInput(Gear.PARK, 0))
        assertEquals(listOf<CheckpointEffect>(CheckpointEffect.Ignored(IgnoreReason.NOT_STARTED)), idle.effects)
        assertEquals(Gear.PARK, idle.state.lastGear)

        val d = Driver(1)
        d.gear(Gear.DRIVE)
        d.deliverOne()
        assertEquals(listOf<CheckpointEffect>(CheckpointEffect.Ignored(IgnoreReason.ALREADY_FINISHED)), d.gear(Gear.PARK))
        assertEquals(
            listOf<CheckpointEffect>(CheckpointEffect.Ignored(IgnoreReason.ALREADY_STARTED)),
            d.send(CheckpointInput.Start(5, 0)),
        )
    }

    @Test
    fun `start while already in drive is armed`() {
        val pre = CheckpointMachine.reduce(CheckpointState(), GearInput(Gear.DRIVE, 0)).state
        val started = CheckpointMachine.reduce(pre, CheckpointInput.Start(2, 1)).state
        assertEquals(CheckpointPhase.EnRoute(0, armed = true), started.phase)
    }

    @Test
    fun `ready to complete only after closed door cycle`() {
        val d = Driver(1)
        val cfg = CheckpointConfig()
        d.gear(Gear.DRIVE)
        d.gear(Gear.PARK)
        assertEquals(false, d.state.readyToComplete(cfg, d.t))
        d.door(true)
        assertEquals(false, d.state.readyToComplete(cfg, d.t))
        d.door(false)
        assertEquals(true, d.state.readyToComplete(cfg, d.t))
    }
}
