package app.nextstop.core.settle

import app.nextstop.core.run.RunSnapshot
import app.nextstop.core.run.StopOutcome
import kotlin.math.roundToLong

/**
 * 정산 입력. 금액은 원 단위 정수.
 * 연비/전비는 같은 식으로 계산한다(내연기관: km/L·원/L, 전기차: km/kWh·원/kWh).
 */
data class SettlementParams(
    val feePerParcelWon: Long,
    val energyPricePerUnitWon: Long,
    val efficiencyKmPerUnit: Double,
    val extraCostWon: Long = 0,
) {
    init {
        require(feePerParcelWon >= 0)
        require(energyPricePerUnitWon >= 0)
        require(efficiencyKmPerUnit > 0)
        require(extraCostWon >= 0)
    }
}

enum class DistanceSource {
    /** 차량 Odometer 실측. */
    ODOMETER,
    /** Odometer가 없어 동선 추정치를 썼다(화면에 '추정'으로 표시). */
    ESTIMATED,
}

data class SettlementReport(
    val deliveredStops: Int,
    val skippedStops: Int,
    val deliveredParcels: Int,
    val distanceKm: Double,
    val distanceSource: DistanceSource,
    val energyUsed: Double,
    val energyCostWon: Long,
    val grossWon: Long,
    val netWon: Long,
    val elapsedMillis: Long,
    /** [Settlement.MIN_MINUTES_FOR_HOURLY]분 미만 운행은 시간당 값을 내지 않는다 — 몇 분짜리 운행을 시간으로 늘리면 수만 원대로 부풀려진다. */
    val netPerHourWon: Long?,
    val costPerParcelWon: Long?,
    val avgDwellMillis: Long?,
) {
    /** 배송지 1곳당 평균 주행거리(km). 배송이 없으면 null. */
    val kmPerStop: Double? get() = if (deliveredStops > 0) distanceKm / deliveredStops else null
}

object Settlement {

    /** 시간당 실수익을 내는 최소 운행 시간(분). 10/30 시연 리허설에서 3분 운행이 시간당 79,054원으로 나왔다. */
    const val MIN_MINUTES_FOR_HOURLY = 30L

    fun settle(
        snapshot: RunSnapshot,
        params: SettlementParams,
        estimatedKm: Double,
        nowMillis: Long,
    ): SettlementReport {
        val delivered = snapshot.records.filter { it.outcome == StopOutcome.DELIVERED }
        val parcels = delivered.sumOf { it.stop.parcels }

        val odoStart = snapshot.odometerStartKm
        val odoEnd = snapshot.odometerLastKm
        val odoDelta = if (odoStart != null && odoEnd != null) odoEnd - odoStart else null
        // 에뮬레이터처럼 차가 실제로 움직이지 않으면 Odometer 변화가 0이다. 그때는 추정치로 정산한다.
        // 신호 원천이 섞여 값이 튀면(예: 0km → 12,010km) 계기판 값을 믿지 않는다 — 동선 추정의 3배·+30km를 넘는 변화는 버린다.
        val plausibleMax = maxOf(estimatedKm * 3, estimatedKm + 30.0)
        val (distance, source) = if (odoDelta != null && odoDelta > 0.0 && odoDelta <= plausibleMax) {
            odoDelta to DistanceSource.ODOMETER
        } else {
            estimatedKm.coerceAtLeast(0.0) to DistanceSource.ESTIMATED
        }

        val energy = distance / params.efficiencyKmPerUnit
        val energyCost = (energy * params.energyPricePerUnitWon).roundToLong()
        val gross = parcels * params.feePerParcelWon
        val net = gross - energyCost - params.extraCostWon

        val start = snapshot.startedAt ?: nowMillis
        val end = snapshot.finishedAt ?: nowMillis
        val elapsed = (end - start).coerceAtLeast(0)
        val perHour = if (elapsed >= MIN_MINUTES_FOR_HOURLY * 60_000) (net * 3_600_000.0 / elapsed).roundToLong() else null

        val dwell = delivered.mapNotNull { it.dwellMillis }
        return SettlementReport(
            deliveredStops = delivered.size,
            skippedStops = snapshot.records.count { it.outcome == StopOutcome.SKIPPED },
            deliveredParcels = parcels,
            distanceKm = distance,
            distanceSource = source,
            energyUsed = energy,
            energyCostWon = energyCost,
            grossWon = gross,
            netWon = net,
            elapsedMillis = elapsed,
            netPerHourWon = perHour,
            costPerParcelWon = if (parcels > 0) ((energyCost + params.extraCostWon).toDouble() / parcels).roundToLong() else null,
            avgDwellMillis = if (dwell.isNotEmpty()) dwell.average().roundToLong() else null,
        )
    }
}
