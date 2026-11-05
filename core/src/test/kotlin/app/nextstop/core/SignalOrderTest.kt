package app.nextstop.core

import app.nextstop.core.checkpoint.CheckpointConfig
import app.nextstop.core.checkpoint.CheckpointEffect
import app.nextstop.core.checkpoint.CheckpointInput
import app.nextstop.core.checkpoint.CheckpointMachine
import app.nextstop.core.checkpoint.CheckpointPhase
import app.nextstop.core.checkpoint.CheckpointState
import app.nextstop.core.checkpoint.CreditReason
import app.nextstop.core.checkpoint.IgnoreReason
import app.nextstop.core.nav.RecordingNavigator
import app.nextstop.core.route.RouteOptimizer
import app.nextstop.core.run.DeliveryRun
import app.nextstop.core.run.RunCodec
import app.nextstop.core.sample.SampleRoutes
import app.nextstop.core.time.ManualClock
import app.nextstop.core.vehicle.DoorUnion
import app.nextstop.core.vehicle.Gear
import app.nextstop.core.vehicle.VehicleEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 심사 지적 대응: 신호 지연·순서 역전(C10), 놓친 D(11/2 리허설 재시작), 시동 끔(A7), 오탐 되돌리기(B4·B6), 조수석·동승(A8·V3),
 * 출발점 고정 좌표 제거(V4).
 */
class SignalOrderTest {

    /** 첫 D로 무장한 뒤 신호를 [steps] 순서·간격으로 넣고, 완료 건수를 센다. */
    private fun run(vararg steps: Pair<String, Long>, config: CheckpointConfig = CheckpointConfig()): Pair<CheckpointState, List<CheckpointEffect>> {
        var t = 0L
        var s = CheckpointMachine.reduce(CheckpointState(), CheckpointInput.Start(3, t), config).state
        val out = mutableListOf<CheckpointEffect>()
        fun send(i: CheckpointInput) {
            val tr = CheckpointMachine.reduce(s, i, config)
            s = tr.state
            out += tr.effects
        }
        send(CheckpointInput.GearInput(Gear.DRIVE, t))
        for ((what, dt) in steps) {
            t += dt
            send(
                when (what) {
                    "P" -> CheckpointInput.GearInput(Gear.PARK, t)
                    "D" -> CheckpointInput.GearInput(Gear.DRIVE, t)
                    "open" -> CheckpointInput.DoorInput(true, t)
                    "close" -> CheckpointInput.DoorInput(false, t)
                    else -> error(what)
                },
            )
        }
        return s to out
    }

    private fun completed(effects: List<CheckpointEffect>) = effects.count { it is CheckpointEffect.StopCompleted }

    @Test
    fun `delayed or reordered signals of one real delivery complete exactly once`() {
        // 실제 순서는 P → 문 열림 → 문 닫힘 → D. 문(Pleos)과 기어(AAOS)는 다른 경로라 받은 순서가 바뀔 수 있다.
        val orders = mapOf(
            "정상" to listOf("P" to 30_000L, "open" to 5_000L, "close" to 60_000L, "D" to 5_000L),
            "P가 문보다 늦게(11/2 리허설에서 본 수십 초 지연)" to listOf("open" to 30_000L, "close" to 20_000L, "P" to 35_000L, "D" to 5_000L),
            "P가 열림과 닫힘 사이에" to listOf("open" to 30_000L, "P" to 10_000L, "close" to 30_000L, "D" to 5_000L),
            "닫힘이 D보다 늦게" to listOf("P" to 30_000L, "open" to 5_000L, "D" to 30_000L, "close" to 8_000L),
            "문 둘 다 D보다 늦게" to listOf("P" to 30_000L, "D" to 40_000L, "open" to 3_000L, "close" to 5_000L),
            "P와 문 모두 늦고 D만 제때" to listOf("open" to 30_000L, "close" to 5_000L, "P" to 20_000L, "D" to 1L),
        )
        for ((name, steps) in orders) {
            val (state, effects) = run(*steps.toTypedArray())
            assertEquals(1, completed(effects), name)
            assertEquals(1, state.currentIndex, "$name: 다음 집으로")
        }
    }

    @Test
    fun `stops without any door are still not deliveries whatever the order`() {
        val (_, a) = run("P" to 30_000L, "D" to 20_000L)
        assertEquals(0, completed(a))
        // 신호 대기 뒤 다음 집까지 가서 배송: 다음 집의 문을 앞 정차로 잘못 붙이지 않는다(CI 리허설 단계 간격 약 6초).
        val (s, b) = run(
            "P" to 30_000L, "D" to 20_000L, // 문 없는 정차
            "P" to 12_000L, "open" to 6_000L, "close" to 6_000L, "D" to 6_000L, // 다음 집
        )
        assertEquals(1, completed(b))
        assertEquals(1, s.currentIndex)
        val credited = b.filterIsInstance<CheckpointEffect.DoorCycleCredited>()
        assertTrue(credited.isEmpty(), "정상 순서에서는 보정이 끼어들지 않는다: $credited")
    }

    @Test
    fun `door signals outside the calibration windows are not credited`() {
        val (_, early) = run("open" to 1_000L, "close" to 1_000L, "P" to 61_000L, "D" to 5_000L)
        assertTrue(CheckpointEffect.Ignored(IgnoreReason.DEPARTED_WITHOUT_DOOR_CYCLE) in early)
        assertEquals(0, completed(early))
        val (_, late) = run("P" to 30_000L, "D" to 30_000L, "open" to 10_000L, "close" to 10_000L)
        assertEquals(0, completed(late), "D 뒤 15초를 넘긴 문 닫힘은 앞 정차로 치지 않는다")
        // 창을 0으로 두면 보정을 끈다(옛 동작)
        val off = CheckpointConfig(doorBeforeParkMillis = 0, doorAfterDepartMillis = 0)
        val (_, none) = run("open" to 1_000L, "close" to 1_000L, "P" to 1_000L, "D" to 5_000L, config = off)
        assertEquals(0, completed(none))
    }

    @Test
    fun `credited completions say why in the effects`() {
        val (_, early) = run("open" to 30_000L, "close" to 5_000L, "P" to 20_000L, "D" to 5_000L)
        assertTrue(CheckpointEffect.DoorCycleCredited(0, CreditReason.DOOR_BEFORE_PARK) in early)
        val (_, late) = run("P" to 30_000L, "D" to 40_000L, "open" to 3_000L, "close" to 5_000L)
        assertTrue(CheckpointEffect.DoorCycleCredited(0, CreditReason.DOOR_AFTER_DEPARTURE) in late)
        val done = late.filterIsInstance<CheckpointEffect.StopCompleted>().single()
        assertEquals(30_000L, done.parkedAt)
        assertEquals(70_000L, done.departedAt, "출발 시각은 늦게 온 문 신호가 아니라 D 시각")
    }

    @Test
    fun `missed drive and park while the app restarted are recovered from the odometer`() {
        // 11/2 CI 리허설(ci-20261102-1406): 내비가 앞에 있는 동안 앱 프로세스가 재시작돼 D·P를 놓쳤고,
        // 복원된 운행은 '아직 출발 전'이라 정차의 문 신호를 버렸다. 계기판이 늘었으면 움직인 것으로 본다.
        val clock = ManualClock(1_000_000)
        val nav = RecordingNavigator()
        val first = DeliveryRun(SampleRoutes.DEMO_5.take(2), nav, clock)
        first.onVehicleEvent(VehicleEvent.GearChanged(Gear.PARK, clock.nowMillis()))
        first.onVehicleEvent(VehicleEvent.OdometerUpdated(0.0, clock.nowMillis()))
        first.start()
        val saved = RunCodec.decode(RunCodec.encode(first.memento()))!!
        // (프로세스 종료 — 그사이 Virtual Console: D → +2km → P)
        clock.advanceSeconds(60)
        val run = DeliveryRun.restore(saved, nav, clock)
        run.onVehicleEvent(VehicleEvent.GearChanged(Gear.PARK, clock.nowMillis())) // 다시 붙을 때 읽은 현재 기어
        run.onVehicleEvent(VehicleEvent.OdometerUpdated(2.0, clock.nowMillis()))
        assertIs<CheckpointPhase.Parked>(run.snapshot().phase)
        clock.advanceSeconds(20); run.onVehicleEvent(VehicleEvent.DoorChanged(true, clock.nowMillis()))
        clock.advanceSeconds(20); run.onVehicleEvent(VehicleEvent.DoorChanged(false, clock.nowMillis()))
        clock.advanceSeconds(20); run.onVehicleEvent(VehicleEvent.GearChanged(Gear.DRIVE, clock.nowMillis()))
        assertEquals(1, run.snapshot().delivered)
    }

    @Test
    fun `odometer creep while loading at the depot does not arm the run`() {
        val clock = ManualClock(1_000_000)
        val run = DeliveryRun(SampleRoutes.DEMO_5.take(2), RecordingNavigator(), clock)
        run.onVehicleEvent(VehicleEvent.GearChanged(Gear.PARK, clock.nowMillis()))
        run.onVehicleEvent(VehicleEvent.OdometerUpdated(100.0, clock.nowMillis()))
        run.start()
        run.onVehicleEvent(VehicleEvent.OdometerUpdated(100.3, clock.nowMillis())) // 후진으로 자리 옮김
        run.onVehicleEvent(VehicleEvent.DoorChanged(true, clock.nowMillis()))
        run.onVehicleEvent(VehicleEvent.DoorChanged(false, clock.nowMillis()))
        val p = run.snapshot().phase
        assertIs<CheckpointPhase.EnRoute>(p)
        assertEquals(false, p.armed)
    }

    @Test
    fun `a power cycle while parked counts as one door cycle, but not while driving`() {
        // 시동 끔: 꺼진 동안 문 이벤트를 받을 수 없다. 다시 켜면 문은 '닫힘'으로 읽혀 사이클 0이 된다(A7).
        val parked = run("P" to 30_000L).first
        val tr = CheckpointMachine.reduce(parked, CheckpointInput.PowerCycled(40_000))
        assertTrue(CheckpointEffect.DoorCycleCredited(0, CreditReason.POWER_CYCLE) in tr.effects)
        val d = CheckpointMachine.reduce(tr.state, CheckpointInput.GearInput(Gear.DRIVE, 300_000))
        assertEquals(1, completed(d.effects))

        val driving = run("P" to 30_000L, "D" to 10_000L).first
        val ignored = CheckpointMachine.reduce(driving, CheckpointInput.PowerCycled(50_000))
        assertEquals(listOf<CheckpointEffect>(CheckpointEffect.Ignored(IgnoreReason.NOT_PARKED)), ignored.effects)
    }

    @Test
    fun `undo puts the wrongly counted stop back in front and removes it from the record`() {
        val clock = ManualClock(1_000_000)
        val nav = RecordingNavigator()
        val stops = SampleRoutes.DEMO_5.take(3)
        val run = DeliveryRun(stops, nav, clock)
        fun ev(e: (Long) -> VehicleEvent) { clock.advanceSeconds(30); run.onVehicleEvent(e(clock.nowMillis())) }
        run.start()
        ev { VehicleEvent.GearChanged(Gear.DRIVE, it) }
        // 편의점 정차: P → 문 → D 가 1건으로 세어짐
        ev { VehicleEvent.GearChanged(Gear.PARK, it) }; ev { VehicleEvent.DoorChanged(true, it) }
        ev { VehicleEvent.DoorChanged(false, it) }; ev { VehicleEvent.GearChanged(Gear.DRIVE, it) }
        assertEquals(1, run.snapshot().delivered)
        assertEquals(stops[1], run.snapshot().currentStop)
        // 진짜 첫 집에 도착해 P → 정차 화면에서 '되돌리기'
        ev { VehicleEvent.GearChanged(Gear.PARK, it) }
        run.undoLast()
        val snap = run.snapshot()
        assertEquals(0, snap.delivered)
        assertEquals(stops[0], snap.currentStop, "방금 센 집이 다시 현재 배송지")
        assertEquals(stops[1], snap.upcomingStop)
        assertEquals(stops[0], nav.requested.last())
        assertTrue(snap.isParked)
        // 이제 진짜 배송
        ev { VehicleEvent.DoorChanged(true, it) }; ev { VehicleEvent.DoorChanged(false, it) }
        ev { VehicleEvent.GearChanged(Gear.DRIVE, it) }
        assertEquals(listOf(stops[0]), run.snapshot().records.map { it.stop })
    }

    @Test
    fun `undo after the last stop reopens the run while parked`() {
        val (finished, _) = run(
            "P" to 30_000L, "open" to 1L, "close" to 1L, "D" to 1L,
            "P" to 30_000L, "open" to 1L, "close" to 1L, "D" to 1L,
            "P" to 30_000L, "open" to 1L, "close" to 1L, "D" to 1L,
        )
        assertEquals(CheckpointPhase.Finished, finished.phase)
        // 주행 중에는 정산 화면이 없으므로 P를 넣은 뒤 되돌린다
        val parked = CheckpointMachine.reduce(finished, CheckpointInput.GearInput(Gear.PARK, 200_000)).state
        val tr = CheckpointMachine.reduce(parked, CheckpointInput.Undo(201_000))
        assertEquals(CheckpointPhase.Parked(2, 201_000), tr.state.phase)
        assertTrue(CheckpointEffect.NavigateTo(2) in tr.effects)
        // 되돌릴 것이 없으면 무시
        val (fresh, _) = run()
        assertEquals(
            listOf<CheckpointEffect>(CheckpointEffect.Ignored(IgnoreReason.NOTHING_TO_UNDO)),
            CheckpointMachine.reduce(fresh, CheckpointInput.Undo(1)).effects,
        )
    }

    @Test
    fun `passenger door cycle counts when front doors are joined`() {
        // 동승자가 내려 배송하거나 벽에 붙여 조수석으로 내린 경우(A8·V3): 운전석 문은 그대로 닫혀 있다.
        val u = DoorUnion()
        val seen = mutableListOf<Boolean>()
        seen += u.update("ROW_1_LEFT", false)
        seen += u.update("ROW_1_RIGHT", false)
        seen += u.update("ROW_1_RIGHT", true)
        seen += u.update("ROW_1_LEFT", true) // 둘 다 열림
        seen += u.update("ROW_1_RIGHT", false) // 아직 운전석이 열림
        seen += u.update("ROW_1_LEFT", false)
        assertEquals(listOf(false, false, true, true, true, false), seen)
        // 묶은 값으로 정차를 돌리면 1건 완료
        val steps = mutableListOf("P" to 30_000L)
        seen.drop(2).forEach { steps += (if (it) "open" else "close") to 2_000L }
        steps += "D" to 5_000L
        assertEquals(1, completed(run(*steps.toTypedArray()).second))
    }

    @Test
    fun `route starts from the first stop without any fixed coordinate`() {
        val stops = SampleRoutes.DEMO_5
        val opt = RouteOptimizer()
        for (start in stops) {
            val list = listOf(start) + (stops - start)
            val plan = opt.planFromFirst(list, keepOrder = false)!!
            assertEquals(start, plan.orderedStops.first(), "사용자가 '출발'로 고른 곳에서 시작")
            assertEquals(start.location, plan.start)
            assertEquals(stops.toSet(), plan.orderedStops.toSet())
            // 나머지는 그 출발점 기준 최적해
            assertEquals(opt.optimize(start.location, stops - start).orderedStops, plan.orderedStops.drop(1))
        }
        assertEquals(null, opt.planFromFirst(emptyList(), keepOrder = false))
        assertEquals(stops, opt.planFromFirst(stops, keepOrder = true)!!.orderedStops)
    }
}
