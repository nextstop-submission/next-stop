package app.nextstop.core

import app.nextstop.core.geo.LatLng
import app.nextstop.core.model.Stop
import app.nextstop.core.nav.RecordingNavigator
import app.nextstop.core.run.DeliveryRun
import app.nextstop.core.run.RunCodec
import app.nextstop.core.sample.SampleRoutes
import app.nextstop.core.time.ManualClock
import app.nextstop.core.vehicle.Gear
import app.nextstop.core.vehicle.ManualSignalSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunCodecTest {

    private val clock = ManualClock(1_767_225_600_000)

    private fun runIn(stops: List<Stop> = SampleRoutes.DEMO_5): Pair<DeliveryRun, ManualSignalSource> {
        val run = DeliveryRun(stops, RecordingNavigator(), clock)
        val car = ManualSignalSource()
        car.start(run)
        car.odometer(100.0, clock.nowMillis())
        run.start()
        car.gear(Gear.DRIVE, clock.nowMillis())
        return run to car
    }

    private fun deliver(car: ManualSignalSource, odo: Double) {
        clock.advanceMinutes(3)
        car.odometer(odo, clock.nowMillis())
        car.gear(Gear.PARK, clock.nowMillis())
        car.door(true, clock.nowMillis())
        car.door(false, clock.nowMillis())
        car.gear(Gear.DRIVE, clock.nowMillis())
    }

    @Test
    fun `round trip mid run keeps progress and continues the same way`() {
        val (run, car) = runIn()
        deliver(car, 101.5)
        deliver(car, 103.0)
        car.gear(Gear.PARK, clock.nowMillis()) // 3번째 배송지에 정차한 채 앱 종료
        car.door(true, clock.nowMillis())

        val text = RunCodec.encode(run.memento())
        val back = RunCodec.decode(text)!!
        assertEquals(run.memento(), back)

        val nav = RecordingNavigator()
        val restored = DeliveryRun.restore(back, nav, clock)
        assertEquals(run.snapshot().copy(readyToComplete = false), restored.snapshot().copy(readyToComplete = false))
        restored.resumeNavigation()
        assertEquals(listOf(run.snapshot().currentStop!!.id), nav.requested.map { it.id })

        // 복원된 운행에서 남은 체크포인트(문 닫고 D)를 이어 하면 3건째가 완료된다.
        val car2 = ManualSignalSource()
        car2.start(restored)
        car2.door(false, clock.nowMillis())
        car2.gear(Gear.DRIVE, clock.nowMillis())
        assertEquals(3, restored.snapshot().delivered)
    }

    @Test
    fun `round trip finished run and labels with special characters`() {
        val tricky = listOf(
            Stop("a\tb", "탭\t줄\n바꿈 %20 & 100%", LatLng(37.4, 127.1), parcels = 2, poiId = "", poiSubId = null),
            Stop("p2", "둘째", LatLng(37.41, 127.11), poiId = "poi-2", poiSubId = "0"),
        )
        val (run, car) = runIn(tricky)
        deliver(car, 101.0)
        run.skipCurrent()
        assertTrue(run.snapshot().isFinished)
        val back = RunCodec.decode(RunCodec.encode(run.memento()))!!
        assertEquals(run.memento(), back)
        assertEquals("탭\t줄\n바꿈 %20 & 100%", back.stops[0].label)
        assertEquals("", back.stops[0].poiId)
        assertNull(back.stops[0].poiSubId)
    }

    @Test
    fun `idle run before start round trips`() {
        val run = DeliveryRun(SampleRoutes.DEMO_5, RecordingNavigator(), clock)
        assertEquals(run.memento(), RunCodec.decode(RunCodec.encode(run.memento())))
    }

    @Test
    fun `broken or foreign input returns null instead of crashing`() {
        assertNull(RunCodec.decode(""))
        assertNull(RunCodec.decode("hello"))
        assertNull(RunCodec.decode("NEXTSTOP-RUN\t2\n"))
        val (run, _) = runIn()
        val good = RunCodec.encode(run.memento())
        assertNull(RunCodec.decode(good.lines().dropLast(2).joinToString("\n"))) // 배송지 일부 잘림
        assertNull(RunCodec.decode(good.replace("enroute", "flying")))
    }

    @Test
    fun `plan list round trips with labels that need escaping`() {
        val stops = listOf(
            Stop("p1", "서울역 1번 출구\t(동쪽)", LatLng(37.5547, 126.9707), parcels = 2, poiId = "p1", poiSubId = "0"),
            Stop("nav-37.41000,127.11000", "", LatLng(37.41, 127.11)),
        )
        val (back, keep) = RunCodec.decodePlan(RunCodec.encodePlan(stops, keepOrder = true))!!
        assertEquals(stops, back)
        assertTrue(keep)
        assertNull(RunCodec.decodePlan("garbage"))
    }
}
