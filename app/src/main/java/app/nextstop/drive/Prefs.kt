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

    /**
     * 기본 가정값(시연용): 건당 750원(대형 이커머스 자차 배송 위탁 계약서 기준 물품 1개당 750원, 세전 — 공개 보도),
     * 휘발유 1,650원/L, 연비 11km/L. 정산 화면에서 −/+로 바꿀 수 있다.
     */
    val DEFAULT = SettlementParams(feePerParcelWon = 750, energyPricePerUnitWon = 1_650, efficiencyKmPerUnit = 11.0)
}
