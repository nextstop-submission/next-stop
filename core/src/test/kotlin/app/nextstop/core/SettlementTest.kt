package app.nextstop.core

import app.nextstop.core.nav.RecordingNavigator
import app.nextstop.core.run.DeliveryRun
import app.nextstop.core.sample.SampleRoutes
import app.nextstop.core.settle.DistanceSource
import app.nextstop.core.settle.Settlement
import app.nextstop.core.settle.SettlementParams
import app.nextstop.core.settle.TripLog
import app.nextstop.core.time.ManualClock
import app.nextstop.core.vehicle.Gear
import app.nextstop.core.vehicle.ManualSignalSource
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettlementTest {

    private val params = SettlementParams(
        feePerParcelWon = 800,
        energyPricePerUnitWon = 1_700,
        efficiencyKmPerUnit = 10.0,
        extraCostWon = 0,
    )
    private val clock = ManualClock(1_767_225_600_000) // 2026-01-01T00:00Z
    private val car = ManualSignalSource()

    private fun runWith(odoStart: Double?, odoEnd: Double?, minutes: Long): DeliveryRun {
        val run = DeliveryRun(SampleRoutes.DEMO_5, RecordingNavigator(), clock)
        car.start(run)
        odoStart?.let { car.odometer(it, clock.nowMillis()) }
        run.start()
        car.gear(Gear.DRIVE, clock.nowMillis())
        val per = minutes / SampleRoutes.DEMO_5.size
        repeat(SampleRoutes.DEMO_5.size) {
            clock.advanceMinutes(per)
            car.gear(Gear.PARK, clock.nowMillis())
            car.door(true, clock.nowMillis())
            car.door(false, clock.nowMillis())
            car.gear(Gear.DRIVE, clock.nowMillis())
        }
        odoEnd?.let { car.odometer(it, clock.nowMillis()) }
        return run
    }

    @Test
    fun `odometer based settlement`() {
        val run = runWith(10_000.0, 10_020.0, minutes = 60)
        // 출발 계기판 이후 마지막 값이 운행 종료 뒤에 들어와도 정산은 실측 거리로 한다
        val r = Settlement.settle(run.snapshot(), params, estimatedKm = 5.0, nowMillis = clock.nowMillis())
        assertEquals(DistanceSource.ODOMETER, r.distanceSource)
        assertEquals(20.0, r.distanceKm, 1e-9)
        assertEquals(2.0, r.energyUsed, 1e-9)
        assertEquals(3_400, r.energyCostWon)
        // 소포 개수 = 1+2+1+1+3 = 8
        assertEquals(8, r.deliveredParcels)
        assertEquals(6_400, r.grossWon)
        assertEquals(3_000, r.netWon)
        assertEquals(3_000, r.netPerHourWon)
        assertEquals(425, r.costPerParcelWon)
        assertEquals(4.0, r.kmPerStop!!, 1e-9)
        assertEquals(0, r.avgDwellMillis)
    }

    @Test
    fun `falls back to estimate when odometer is static`() {
        val run = runWith(10_000.0, 10_000.0, minutes = 30)
        val r = Settlement.settle(run.snapshot(), params, estimatedKm = 7.5, nowMillis = clock.nowMillis())
        assertEquals(DistanceSource.ESTIMATED, r.distanceSource)
        assertEquals(7.5, r.distanceKm, 1e-9)
        assertEquals(1_275, r.energyCostWon)
        assertEquals(10_250, r.netPerHourWon) // (6400-1275) * 2
    }

    @Test
    fun `ignores an implausible odometer jump from mixed sources`() {
        // 실측에서 본 경우: 실차 원천 0km로 시작 → 시연 원천 12,010km로 끝(원천이 섞임)
        val run = runWith(0.0, 12_010.0, minutes = 30)
        val r = Settlement.settle(run.snapshot(), params, estimatedKm = 8.1, nowMillis = clock.nowMillis())
        assertEquals(DistanceSource.ESTIMATED, r.distanceSource)
        assertEquals(8.1, r.distanceKm, 1e-9)
    }

    @Test
    fun `falls back to estimate without odometer`() {
        val run = runWith(null, null, minutes = 30)
        val r = Settlement.settle(run.snapshot(), params, estimatedKm = 3.0, nowMillis = clock.nowMillis())
        assertEquals(DistanceSource.ESTIMATED, r.distanceSource)
        assertNull(TripLog.rowFor(run.snapshot(), ZoneId.of("Asia/Seoul")))
    }

    @Test
    fun `runs under 30 minutes have no hourly figure`() {
        // 시연처럼 몇 분 만에 끝난 운행: 3분이면 시간당 값이 20배로 부풀려진다
        val short = Settlement.settle(runWith(10_000.0, 10_010.0, minutes = 25).snapshot(), params, 10.0, clock.nowMillis())
        assertNull(short.netPerHourWon)
        assertEquals(6_400 - 1_700, short.netWon) // 순수익은 그대로 낸다
    }

    @Test
    fun `hourly figure appears from 30 minutes`() {
        val r = Settlement.settle(runWith(10_000.0, 10_010.0, minutes = 30).snapshot(), params, 10.0, clock.nowMillis())
        assertEquals((6_400 - 1_700) * 2L, r.netPerHourWon)
    }

    @Test
    fun `short runs have no hourly figure`() {
        val run = DeliveryRun(SampleRoutes.DEMO_5, RecordingNavigator(), clock)
        run.start()
        clock.advanceSeconds(30)
        val r = Settlement.settle(run.snapshot(), params, 1.0, clock.nowMillis())
        assertNull(r.netPerHourWon)
        assertNull(r.costPerParcelWon)
    }

    @Test
    fun `extra cost reduces net`() {
        val run = runWith(0.0, 10.0, minutes = 60)
        val r = Settlement.settle(run.snapshot(), params.copy(extraCostWon = 1_000), 0.0, clock.nowMillis())
        assertEquals(6_400 - 1_700 - 1_000, r.netWon)
    }

    @Test
    fun `invalid params rejected`() {
        assertFailsWith<IllegalArgumentException> { params.copy(efficiencyKmPerUnit = 0.0) }
        assertFailsWith<IllegalArgumentException> { params.copy(feePerParcelWon = -1) }
    }

    @Test
    fun `trip log row and csv`() {
        val run = runWith(10_000.0, 10_020.4, minutes = 60)
        val row = TripLog.rowFor(run.snapshot(), ZoneId.of("Asia/Seoul"))!!
        assertEquals("2026-01-01(목)", row.date)
        assertEquals(20.4, row.drivenKm, 1e-6)
        assertEquals(row.drivenKm, row.businessKm)
        val csv = TripLog.toCsv(listOf(row))
        assertTrue(csv.startsWith("﻿사용일자,부서,성명"))
        assertTrue(csv.lines()[1].startsWith("2026-01-01(목),,,10000.0,10020.4,20.4,0.0,20.4,배송 5건"))
    }
}
