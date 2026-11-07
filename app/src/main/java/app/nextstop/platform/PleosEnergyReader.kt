package app.nextstop.platform

import android.util.Log

/**
 * 차가 계산한 평균 연비·전비 = Pleos Vehicle SDK Fuel·EvBattery(권한 `pleos.car.permission.CAR_ENERGY`).
 * 메서드는 SDK 2.3.2 바이너리(javap)에서 확인: Fuel.getCurrentFuelEconomy / getCumulativeFuelEconomy,
 * EvBattery.getCurrentEvEfficiency / getCumulativeEvEfficiency — 모두 (onComplete(Float), onFailed(Exception)).
 * 이번 주행 평균(current)을 먼저, 없으면 누적 평균(cumulative)을 쓴다. 단위는 km/L·km/kWh로 보고 범위 밖 값은
 * core의 EfficiencyChoice가 버린다. 원값은 logcat `NS|ENERGY`에 남겨 에뮬레이터 실측으로 확인한다.
 *
 * 콜백은 바인더 스레드에서 올 수 있어 결과는 메인 스레드로 옮겨 [onResult]에 넘긴다(값 없음 = null).
 */
class PleosEnergyReader(private val session: PleosVehicleSession) {

    fun read(onResult: (fuelKmPerL: Double?, evKmPerKwh: Double?) -> Unit) {
        var fuel: Double? = null
        var ev: Double? = null
        var pending = 2
        fun done() {
            if (--pending == 0) {
                Log.i("NextStop", "NS|ENERGY|fuelKmPerL=$fuel|evKmPerKwh=$ev")
                onResult(fuel, ev)
            }
        }
        if (!session.initialize()) {
            Log.i("NextStop", "NS|ENERGY|init-failed|${session.initError?.let { VehicleErrors.describe(it) }}")
            onResult(null, null); return
        }
        firstPositive(
            listOf(
                { ok, fail -> session.vehicle.getFuel().getCurrentFuelEconomy(ok, fail) },
                { ok, fail -> session.vehicle.getFuel().getCumulativeFuelEconomy(ok, fail) },
            ),
            "fuel",
        ) { v -> mainHandler.post { fuel = v; done() } }
        firstPositive(
            listOf(
                { ok, fail -> session.vehicle.getEvBattery().getCurrentEvEfficiency(ok, fail) },
                { ok, fail -> session.vehicle.getEvBattery().getCumulativeEvEfficiency(ok, fail) },
            ),
            "ev",
        ) { v -> mainHandler.post { ev = v; done() } }
    }

    private fun firstPositive(
        calls: List<((Float?) -> Unit, (Exception) -> Unit) -> Unit>,
        tag: String,
        result: (Double?) -> Unit,
    ) {
        if (calls.isEmpty()) { result(null); return }
        val next = { firstPositive(calls.drop(1), tag, result) }
        try {
            calls.first()(
                { v ->
                    val d = v?.toDouble()
                    Log.i("NextStop", "NS|ENERGY|$tag|raw=$v")
                    if (d != null && d > 0.0) result(d) else next()
                },
                { e -> Log.i("NextStop", "NS|ENERGY|$tag|failed|${VehicleErrors.describe(e)}"); next() },
            )
        } catch (e: Exception) {
            Log.i("NextStop", "NS|ENERGY|$tag|threw|${VehicleErrors.describe(e)}")
            next()
        }
    }
}
