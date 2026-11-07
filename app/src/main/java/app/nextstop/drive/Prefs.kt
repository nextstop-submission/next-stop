package app.nextstop.drive

import android.content.Context
import app.nextstop.core.settle.SettlementParams

/** 사용자 설정(기기에만 저장). */
object Prefs {
    private const val FILE = "nextstop"
    private const val KEY_VOICE = "voice"

    fun voice(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_VOICE, false)

    fun setVoice(c: Context, on: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_VOICE, on).apply()
    }

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 완료로 칠 문: true = 1열 운전석·조수석 중 하나라도(기본), false = 운전석만.
     * 기본을 1열 모두로 둔 근거: 자체 후기 표본 52건 중 2인 동승 배송 3건(docs/research/후기_분석.md #2·#3·#19),
     * 벽에 붙여 세우고 조수석으로 내리는 경우, 우핸들 차량. 조수석 문이 열리는 비배송 정차는 운전석 문이 열리는 비배송 정차와
     * 성격이 같아(어차피 사람이 내림) 오탐을 새로 만드는 경우가 드물고, 생기면 정차 중 '되돌리기'로 고친다.
     */
    fun frontDoors(c: Context): Boolean = p(c).getBoolean("front_doors", true)
    fun setFrontDoors(c: Context, on: Boolean) = p(c).edit().putBoolean("front_doors", on).apply()

    /** 신호 순서 역전 보정 창(ms, 보정 손잡이). adb: `--el door_before_park_ms 60000 --el door_after_depart_ms 15000`. */
    fun doorBeforeParkMs(c: Context, default: Long): Long = p(c).getLong("door_before_park_ms", default)
    fun doorAfterDepartMs(c: Context, default: Long): Long = p(c).getLong("door_after_depart_ms", default)
    fun setDoorWindows(c: Context, beforeParkMs: Long?, afterDepartMs: Long?) {
        p(c).edit().apply {
            beforeParkMs?.let { putLong("door_before_park_ms", it.coerceIn(0, MAX_WINDOW_MS)) }
            afterDepartMs?.let { putLong("door_after_depart_ms", it.coerceIn(0, MAX_WINDOW_MS)) }
        }.apply()
    }
    private const val MAX_WINDOW_MS = 300_000L

    /** 운행 상태를 마지막으로 저장할 때의 부팅 횟수(시동 끔 → 헤드유닛 재부팅 감지). */
    fun runBootCount(c: Context): Int = p(c).getInt("run_boot_count", -1)
    fun setRunBootCount(c: Context, n: Int) = p(c).edit().putInt("run_boot_count", n).apply()

    /** 정산 가정값. 사용자가 자기 단가·연비로 바꿀 수 있다. */
    fun settlement(c: Context): SettlementParams {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return SettlementParams(
            feePerParcelWon = p.getLong("fee", DEFAULT.feePerParcelWon),
            energyPricePerUnitWon = p.getLong("price", DEFAULT.energyPricePerUnitWon),
            efficiencyKmPerUnit = p.getFloat("eff", DEFAULT.efficiencyKmPerUnit.toFloat()).toDouble(),
        )
    }

    fun setSettlement(c: Context, v: SettlementParams) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong("fee", v.feePerParcelWon)
            .putLong("price", v.energyPricePerUnitWon)
            .putFloat("eff", v.efficiencyKmPerUnit.toFloat())
            .apply()
    }

    /** 전기 단가(원/kWh). 차가 전비를 줄 때(전기차) 정산에 쓴다. */
    fun evPrice(c: Context): Long = p(c).getLong("price_kwh", DEFAULT_EV_PRICE_WON)
    fun setEvPrice(c: Context, won: Long) = p(c).edit().putLong("price_kwh", won).apply()

    /**
     * 전기 단가 기본값 350원/kWh(가정): 공공 급속충전 요금이 2026년 8월 출력별 5단계로 바뀌어 kWh당 약 295~393원이다(보도).
     * 사람마다 완속·급속·집 충전이 달라 정산 화면에서 −/+로 바꾼다.
     */
    const val DEFAULT_EV_PRICE_WON = 350L

    /**
     * 기본 가정값(시연용): 건당 750원(대형 이커머스 자차 배송 위탁 계약서 기준 물품 1개당 750원, 세전 — 공개 보도),
     * 휘발유 1,650원/L, 연비 11km/L. 정산 화면에서 −/+로 바꿀 수 있다.
     */
    val DEFAULT = SettlementParams(feePerParcelWon = 750, energyPricePerUnitWon = 1_650, efficiencyKmPerUnit = 11.0)
}
