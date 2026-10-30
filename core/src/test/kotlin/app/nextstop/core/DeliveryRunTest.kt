package app.nextstop.core

import app.nextstop.core.checkpoint.CheckpointEffect
import app.nextstop.core.display.CompletionCue
import app.nextstop.core.display.DriveDisplay
import app.nextstop.core.nav.RecordingNavigator
import app.nextstop.core.run.DeliveryRun
import app.nextstop.core.run.RunSnapshot
import app.nextstop.core.run.StopOutcome
import app.nextstop.core.sample.SampleRoutes
import app.nextstop.core.time.ManualClock
import app.nextstop.core.vehicle.Gear
import app.nextstop.core.vehicle.ManualSignalSource
import app.nextstop.core.vehicle.VehicleEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 목 차량 신호 + 목 내비로 운행 전체를 재현한다(에뮬레이터 시연 시나리오와 동일한 순서). */
class DeliveryRunTest {

    private val clock = ManualClock(1_700_000_000_000)
    private val nav = RecordingNavigator()
    private val stops = SampleRoutes.DEMO_5.take(3)
    private val run = DeliveryRun(stops, nav, clock)
    private val car = ManualSignalSource("mock")
    private val cues = mutableListOf<CompletionCue>()
    private var last: RunSnapshot? = null

    init {
        car.start(run)
        run.addListener { snap, effects ->
            DriveDisplay.completionCue(last, snap, effects)?.let(cues::add)
            last = snap
        }
    }

    private fun now() = clock.nowMillis()

    private fun deliver(odoKm: Double? = null) {
        clock.advanceMinutes(4)
        odoKm?.let { car.odometer(it, now()) }
        car.gear(Gear.PARK, now())
        clock.advanceSeconds(5)
        car.door(true, now())
        clock.advanceSeconds(5)
        car.door(false, now())
        clock.advanceSeconds(60)
        car.door(true, now())
        clock.advanceSeconds(5)
        car.door(false, now())
        clock.advanceSeconds(5)
        car.gear(Gear.DRIVE, now())
    }

    @Test
    fun `full run sends one destination at a time and records every stop`() {
        car.gear(Gear.PARK, now())
        car.odometer(12_000.0, now())
        run.start()
        assertEquals(listOf("s01"), nav.requested.map { it.id })

        car.gear(Gear.DRIVE, now())
        deliver(12_001.2)
        assertEquals(listOf("s01", "s02"), nav.requested.map { it.id })
        deliver(12_003.0)
        deliver(12_004.5)

        val snap = run.snapshot()
        assertTrue(snap.isFinished)
        assertEquals(3, snap.delivered)
        assertEquals(1, nav.cancelCount)
        assertEquals(listOf("s01", "s02", "s03"), nav.requested.map { it.id })
        assertEquals(12_000.0, snap.odometerStartKm)
        assertEquals(listOf(1.2, 1.8, 1.5), snap.records.map { it.legKm!! }.map { Math.round(it * 10) / 10.0 })
        assertEquals(80_000L, snap.records.first().dwellMillis)
    }

    @Test
    fun `completion cue carries count and next label for the transition scene`() {
        run.start()
        car.gear(Gear.DRIVE, now())
        deliver()
        val cue = cues.single()
        assertEquals(1, cue.completedCount)
        assertEquals(3, cue.total)
        assertEquals("가람동 1구역", cue.completedLabel)
        assertEquals("나래동 2구역", cue.nextLabel)
        deliver()
        deliver()
        assertTrue(cues.last().isLast)
        assertEquals(3, cues.last().completedCount)
    }

    @Test
    fun `defer moves current stop to the end and navigates to the next one`() {
        run.start()
        car.gear(Gear.DRIVE, now())
        run.deferCurrent()
        assertEquals(listOf("s02", "s03", "s01"), run.snapshot().stops.map { it.id })
        assertEquals(listOf("s01", "s02"), nav.requested.map { it.id })
        deliver()
        deliver()
        deliver()
        assertEquals(listOf("s02", "s03", "s01"), run.snapshot().records.map { it.stop.id })
    }

    @Test
    fun `defer on the last remaining stop does nothing`() {
        val single = DeliveryRun(stops.take(1), nav, clock)
        single.start()
        single.deferCurrent()
        assertEquals(listOf("s01"), nav.requested.map { it.id })
    }

    @Test
    fun `skip records skipped outcome`() {
        run.start()
        run.skipCurrent()
        val rec = run.snapshot().records.single()
        assertEquals(StopOutcome.SKIPPED, rec.outcome)
        assertEquals(0, run.snapshot().delivered)
    }

    @Test
    fun `odometer that never changes keeps legs at zero and backwards values are dropped`() {
        car.odometer(500.0, now())
        run.start()
        car.odometer(499.0, now()) // 역행 값
        car.gear(Gear.DRIVE, now())
        deliver()
        val snap = run.snapshot()
        assertEquals(500.0, snap.odometerStartKm)
        assertEquals(500.0, snap.odometerLastKm)
        assertEquals(0.0, snap.records.single().legKm)
    }

    @Test
    fun `without odometer legs are null`() {
        run.start()
        car.gear(Gear.DRIVE, now())
        deliver()
        assertNull(run.snapshot().records.single().legKm)
    }

    @Test
    fun `source errors are kept briefly and do not affect state`() {
        run.start()
        repeat(7) { car.emit(VehicleEvent.SourceError("door", "e$it", now())) }
        val snap = run.snapshot()
        assertEquals(listOf("door: e2", "door: e3", "door: e4", "door: e5", "door: e6"), snap.sourceErrors)
        assertEquals(0, snap.done)
    }

    @Test
    fun `ready hint appears after door closes while parked`() {
        run.start()
        car.gear(Gear.DRIVE, now())
        car.gear(Gear.PARK, now())
        car.door(true, now())
        assertEquals("문을 닫고 D를 넣으면 완료", DriveDisplay.card(run.snapshot(), restricted = false).hint)
        car.door(false, now())
        assertEquals("D를 넣으면 완료 처리됩니다", DriveDisplay.card(run.snapshot(), restricted = false).hint)
    }

    @Test
    fun `listener receives completion effects`() {
        val seen = mutableListOf<CheckpointEffect>()
        run.addListener { _, effects -> seen += effects }
        run.start()
        car.gear(Gear.DRIVE, now())
        deliver()
        assertTrue(seen.any { it is CheckpointEffect.StopCompleted })
    }
}
