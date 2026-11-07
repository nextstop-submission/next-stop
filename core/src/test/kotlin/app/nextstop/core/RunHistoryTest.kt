package app.nextstop.core

import app.nextstop.core.settle.Efficiency
import app.nextstop.core.settle.EfficiencyChoice
import app.nextstop.core.settle.EfficiencySource
import app.nextstop.core.settle.EnergyUnit
import app.nextstop.core.settle.RunHistory
import app.nextstop.core.settle.RunSummary
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunHistoryTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val hour = 3_600_000L
    // 2026-01-01 00:00 KST
    private val day0 = 1_767_193_200_000L

    private fun run(dayOffset: Int, startHourKst: Int, hours: Double, net: Long) =
        RunSummary(day0 + dayOffset * 24 * hour + startHourKst * hour, (hours * hour).toLong(), 40, 30.0, net)

    @Test
    fun `this run is compared with the average hourly net of past runs and the best start hour is named`() {
        val past = listOf(
            run(0, 19, 2.0, 30_000), // 시간당 15,000
            run(1, 9, 2.0, 50_000),  // 시간당 25,000 ← 가장 높음
            run(2, 20, 2.0, 20_000), // 시간당 10,000
        )
        val now = run(3, 19, 2.0, 24_000) // 시간당 12,000
        val c = RunHistory.compare(past + now, now, seoul)
        assertEquals(3, c.pastCount)
        assertEquals(16_667, c.pastAvgPerHourWon)
        assertEquals(12_000 - 16_667, c.diffPerHourWon)
        assertEquals(9, c.bestStartHour)
        assertEquals(25_000, c.bestPerHourWon)
    }

    @Test
    fun `short runs have no hourly value and are left out of the comparison`() {
        val past = listOf(run(0, 19, 0.2, 5_000), run(1, 19, 2.0, 20_000))
        val now = run(2, 19, 0.3, 3_000)
        val c = RunHistory.compare(past, now, seoul)
        assertEquals(1, c.pastCount)
        assertNull(c.diffPerHourWon, "30분 미만 운행은 시간당 값이 없다")
    }

    @Test
    fun `history keeps one line per run, only numbers, and survives a broken line`() {
        var h = emptyList<RunSummary>()
        repeat(RunHistory.MAX_RUNS + 5) { h = RunHistory.append(h, run(it, 19, 2.0, 1_000L * it)) }
        h = RunHistory.append(h, h.last()) // 같은 운행을 다시 넣어도 한 줄
        assertEquals(RunHistory.MAX_RUNS, h.size)
        val text = RunHistory.encode(h) + "\n깨진 줄,x\n"
        assertEquals(h, RunHistory.decode(text))
        assert(RunHistory.encode(h).all { it.isDigit() || it in ",.\n-" }) { "운행 기록에는 숫자만" }
    }

    @Test
    fun `vehicle efficiency is used when plausible, otherwise the user value`() {
        val user = Efficiency(11.0, EnergyUnit.LITER, EfficiencySource.USER)
        assertEquals(Efficiency(13.4, EnergyUnit.LITER, EfficiencySource.VEHICLE), EfficiencyChoice.choose(13.4, null, user))
        assertEquals(Efficiency(5.1, EnergyUnit.KWH, EfficiencySource.VEHICLE), EfficiencyChoice.choose(0.0, 5.1, user))
        assertEquals(user, EfficiencyChoice.choose(0.0, 0.0, user), "센서 초기값 0은 버린다")
        assertEquals(user, EfficiencyChoice.choose(120.0, null, user), "단위가 다른 값(예: mpg·L/100km 혼동)은 버린다")
        assertEquals(user, EfficiencyChoice.choose(null, null, user))
    }
}
