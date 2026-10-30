package app.nextstop.core

import app.nextstop.core.vehicle.CompositeSignalSource
import app.nextstop.core.vehicle.FirstGearWinsSource
import app.nextstop.core.vehicle.Gear
import app.nextstop.core.vehicle.ManualSignalSource
import app.nextstop.core.vehicle.VehicleEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class SignalSourceTest {

    @Test
    fun `first gear source wins and late duplicates from the other are dropped`() {
        val a = ManualSignalSource("a")
        val b = ManualSignalSource("b")
        val got = mutableListOf<VehicleEvent>()
        val arb = FirstGearWinsSource(listOf(a, b))
        arb.start { got += it }

        a.gear(Gear.PARK, 1)
        a.gear(Gear.DRIVE, 2)
        b.gear(Gear.PARK, 3) // 늦게 도착한 중복 → 버림
        b.door(true, 4) // 도어는 통과
        assertEquals("a", arb.gearOwner)
        assertEquals(
            listOf(VehicleEvent.GearChanged(Gear.PARK, 1), VehicleEvent.GearChanged(Gear.DRIVE, 2), VehicleEvent.DoorChanged(true, 4)),
            got,
        )
    }

    @Test
    fun `owner is whichever source reports first`() {
        val a = ManualSignalSource("a")
        val b = ManualSignalSource("b")
        val arb = FirstGearWinsSource(listOf(a, b))
        val got = mutableListOf<VehicleEvent>()
        arb.start { got += it }
        b.gear(Gear.DRIVE, 1)
        a.gear(Gear.PARK, 2)
        assertEquals("b", arb.gearOwner)
        assertEquals(listOf<VehicleEvent>(VehicleEvent.GearChanged(Gear.DRIVE, 1)), got)
    }

    @Test
    fun `silent owner hands over to a source that reports a new value`() {
        val a = ManualSignalSource("a")
        val b = ManualSignalSource("b")
        val arb = FirstGearWinsSource(listOf(a, b), handoverMillis = 2_000)
        val got = mutableListOf<VehicleEvent>()
        arb.start { got += it }
        a.gear(Gear.PARK, 0) // a는 초기값만 내고 이후 조용
        b.gear(Gear.PARK, 100) // 같은 값 → 무시
        b.gear(Gear.DRIVE, 1_000) // 2초 전 → 무시(늦은 중복일 수 있음)
        b.gear(Gear.DRIVE, 3_000) // a가 3초 조용 + 새 값 → b가 넘겨받음
        a.gear(Gear.PARK, 3_200) // 이제 a는 owner 아님, 2초 안 → 무시
        assertEquals("b", arb.gearOwner)
        assertEquals(listOf(Gear.PARK, Gear.DRIVE), got.map { (it as VehicleEvent.GearChanged).gear })
    }

    @Test
    fun `composite forwards all and stops all`() {
        val a = ManualSignalSource("a")
        val b = ManualSignalSource("b")
        val got = mutableListOf<VehicleEvent>()
        val c = CompositeSignalSource(listOf(a, b))
        c.start { got += it }
        a.door(true, 1)
        b.odometer(10.0, 2)
        c.stop()
        a.door(false, 3)
        assertEquals(2, got.size)
        assertEquals(false, a.isStarted)
        assertEquals("a+b", c.name)
    }
}
