package app.nextstop.platform

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context

/**
 * Pleos Vehicle SDK 세션 1개를 앱 전체에서 공유한다.
 * 공식 샘플 순서: Vehicle(context) → initialize() → getXxx() 도메인 사용 → release().
 */
class PleosVehicleSession(context: Context) {
    val vehicle = Vehicle(context.applicationContext)
    var initError: Exception? = null
        private set
    var isInitialized = false
        private set

    fun initialize(): Boolean {
        if (isInitialized) return true
        return try {
            vehicle.initialize()
            isInitialized = true
            initError = null
            true
        } catch (e: Exception) {
            initError = e
            false
        }
    }

    fun release() {
        if (!isInitialized) return
        runCatching { vehicle.release() }
        isInitialized = false
    }
}
