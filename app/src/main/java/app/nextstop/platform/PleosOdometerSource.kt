package app.nextstop.platform

import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import app.nextstop.core.vehicle.VehicleEvent
import app.nextstop.core.vehicle.VehicleEventListener
import app.nextstop.core.vehicle.VehicleSignalSource

/**
 * 누적 주행거리 = Pleos Vehicle SDK Odometer.
 * 메서드는 SDK 2.3.2 바이너리에서 확인: getCurrentDistanceDriven(onComplete, onFailed),
 * registerDistanceDriven / unregisterDistanceDriven(DistanceDrivenListener { onDistanceDrivenUpdated(Float) }).
 *
 * 시그니처·nullability는 SDK aar의 javap(CI 'Pleos SDK signatures' 단계)로 확인.
 * 값 단위는 실측으로 확인(km 가정, 표시 단위 상수는 constant/unit/DistanceDisplayUnit).
 */
class PleosOdometerSource(private val session: PleosVehicleSession) : VehicleSignalSource {
    override val name = "pleos-odometer"
    private var sink: VehicleEventListener? = null
    private var listener: DistanceDrivenListener? = null

    override fun start(listener: VehicleEventListener) {
        sink = MainThreadListener(listener)
        attach(1)
    }

    private fun attach(attempt: Int) {
        if (sink == null || listener != null) return
        try {
            if (!session.initialize()) throw session.initError ?: IllegalStateException("Vehicle initialize failed")
            val odometer = session.vehicle.getOdometer()
            odometer.getCurrentDistanceDriven(
                onComplete = { value -> emit(value) },
                onFailed = { reportError(it) },
            )
            val l = object : DistanceDrivenListener {
                override fun onDistanceDrivenUpdated(distance: Float?) = emit(distance)
                override fun onFailed(exception: Exception) = reportError(exception)
            }
            odometer.registerDistanceDriven(l)
            this.listener = l
        } catch (e: Exception) {
            if (attempt < 4) mainHandler.postDelayed({ attach(attempt + 1) }, 1_500L) else reportError(e)
        }
    }

    override fun stop() {
        listener?.let { l -> runCatching { session.vehicle.getOdometer().unregisterDistanceDriven(l) } }
        listener = null
        sink = null
    }

    private fun emit(value: Any?) {
        val km = (value as? Number)?.toDouble() ?: return
        sink?.onVehicleEvent(VehicleEvent.OdometerUpdated(km, now()))
    }

    private fun reportError(t: Throwable) {
        sink?.onVehicleEvent(VehicleEvent.SourceError(name, VehicleErrors.describe(t), now()))
    }
}
