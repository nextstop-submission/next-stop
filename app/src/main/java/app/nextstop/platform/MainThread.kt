package app.nextstop.platform

import android.os.Handler
import android.os.Looper
import app.nextstop.core.vehicle.VehicleEvent
import app.nextstop.core.vehicle.VehicleEventListener

internal val mainHandler = Handler(Looper.getMainLooper())

internal fun onMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
}

/** SDK 콜백(바인더 스레드)을 메인 스레드로 옮긴다. DeliveryRun은 단일 스레드 전제. */
internal class MainThreadListener(private val target: VehicleEventListener) : VehicleEventListener {
    override fun onVehicleEvent(event: VehicleEvent) = onMain { target.onVehicleEvent(event) }
}

internal fun now(): Long = System.currentTimeMillis()
