package app.nextstop.core.settle

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * 끝난 운행 한 줄 — 숫자만(배송지 이름·좌표 없음). '어느 운행이 남고 어느 운행이 손해였나'를 비교하는 재료.
 * 후기 1위 불편('기름값 빼면 남는 게 적다')에 대해 앱이 단가를 올릴 수는 없다. 대신 운행마다 차가 기록한 숫자로
 * 시간대·물량별 실수익을 나란히 보여, 덜 남는 운행을 사람이 골라낼 수 있게 한다.
 */
data class RunSummary(
    val startedAt: Long,
    val elapsedMillis: Long,
    val deliveredParcels: Int,
    val distanceKm: Double,
    val netWon: Long,
) {
    /** [Settlement.MIN_MINUTES_FOR_HOURLY]분 미만이면 null(정산 화면과 같은 기준). */
    val netPerHourWon: Long?
        get() = if (elapsedMillis >= Settlement.MIN_MINUTES_FOR_HOURLY * 60_000) (netWon * 3_600_000.0 / elapsedMillis).roundToLong() else null

    fun startHour(zone: ZoneId): Int = Instant.ofEpochMilli(startedAt).atZone(zone).hour
}

/** 이번 운행을 지난 운행들과 비교한 결과. */
data class RunComparison(
    /** 시간당 값이 있는 지난 운행 수. */
    val pastCount: Int,
    val pastAvgPerHourWon: Long?,
    /** 이번 운행 시간당 − 지난 평균. 둘 중 하나라도 없으면 null. */
    val diffPerHourWon: Long?,
    /** 지난 운행 중 시간당 실수익이 가장 높았던 운행의 시작 시각(시). */
    val bestStartHour: Int?,
    val bestPerHourWon: Long?,
)

object RunHistory {
    /** 기기에 남기는 최대 운행 수(약 두 달 치). 오래된 것부터 지운다. */
    const val MAX_RUNS = 60

    fun summarize(snapshot: app.nextstop.core.run.RunSnapshot, report: SettlementReport): RunSummary? {
        val start = snapshot.startedAt ?: return null
        if (report.deliveredStops == 0) return null
        return RunSummary(start, report.elapsedMillis, report.deliveredParcels, report.distanceKm, report.netWon)
    }

    /** 같은 운행(시작 시각)을 두 번 넣지 않고, 최근 [MAX_RUNS]개만 남긴다. */
    fun append(history: List<RunSummary>, run: RunSummary): List<RunSummary> =
        (history.filter { it.startedAt != run.startedAt } + run).sortedBy { it.startedAt }.takeLast(MAX_RUNS)

    fun compare(history: List<RunSummary>, current: RunSummary, zone: ZoneId): RunComparison {
        val past = history.filter { it.startedAt != current.startedAt && it.netPerHourWon != null }
        val avg = if (past.isEmpty()) null else past.map { it.netPerHourWon!! }.average().roundToLong()
        val best = past.maxByOrNull { it.netPerHourWon!! }
        return RunComparison(
            pastCount = past.size,
            pastAvgPerHourWon = avg,
            diffPerHourWon = if (avg != null && current.netPerHourWon != null) current.netPerHourWon!! - avg else null,
            bestStartHour = best?.startHour(zone),
            bestPerHourWon = best?.netPerHourWon,
        )
    }

    /** 한 줄에 한 운행: `시작ms,경과ms,개수,km,순수익`. 숫자만이라 개인정보가 없다. */
    fun encode(history: List<RunSummary>): String = history.joinToString("\n") {
        "${it.startedAt},${it.elapsedMillis},${it.deliveredParcels},${it.distanceKm},${it.netWon}"
    }

    /** 깨진 줄은 건너뛴다(기록 파일 하나가 정산 화면을 막지 않게). */
    fun decode(text: String): List<RunSummary> = text.lineSequence().mapNotNull { line ->
        val f = line.trim().split(',')
        if (f.size != 5) return@mapNotNull null
        runCatching { RunSummary(f[0].toLong(), f[1].toLong(), f[2].toInt(), f[3].toDouble(), f[4].toLong()) }.getOrNull()
    }.toList()
}
