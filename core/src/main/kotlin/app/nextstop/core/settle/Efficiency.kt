package app.nextstop.core.settle

/** 연료 단위. 내연기관은 L, 전기차는 kWh — 정산 식은 같다(거리 ÷ 효율 × 단가). */
enum class EnergyUnit(val label: String) { LITER("L"), KWH("kWh") }

enum class EfficiencySource {
    /** 차가 계산한 평균 연비·전비(Pleos Vehicle SDK Fuel·EvBattery). */
    VEHICLE,
    /** 사용자가 정산 화면에서 넣은 값(차가 값을 주지 않을 때). */
    USER,
}

data class Efficiency(val kmPerUnit: Double, val unit: EnergyUnit, val source: EfficiencySource)

/**
 * 정산에 쓸 연비·전비 고르기. 차가 준 값이 그럴듯하면 차 값을, 아니면 사용자 값을 쓴다.
 * 범위를 벗어난 값(0, 단위가 다른 값, 센서 초기값)은 버린다 — 정산이 몇 배로 틀리는 것보다 사용자 값이 낫다.
 */
object EfficiencyChoice {
    /** 승용·소형 화물 내연기관 연비로 그럴듯한 범위(km/L). */
    val FUEL_RANGE = 3.0..40.0
    /** 승용·소형 화물 전기차 전비로 그럴듯한 범위(km/kWh). */
    val EV_RANGE = 2.0..10.0

    fun choose(vehicleKmPerL: Double?, vehicleKmPerKwh: Double?, user: Efficiency): Efficiency = when {
        vehicleKmPerL != null && vehicleKmPerL in FUEL_RANGE -> Efficiency(vehicleKmPerL, EnergyUnit.LITER, EfficiencySource.VEHICLE)
        vehicleKmPerKwh != null && vehicleKmPerKwh in EV_RANGE -> Efficiency(vehicleKmPerKwh, EnergyUnit.KWH, EfficiencySource.VEHICLE)
        else -> user
    }
}
