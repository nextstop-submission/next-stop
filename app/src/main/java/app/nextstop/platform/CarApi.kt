package app.nextstop.platform

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import app.nextstop.core.vehicle.Gear
import app.nextstop.core.vehicle.VehicleEvent
import app.nextstop.core.vehicle.VehicleEventListener
import app.nextstop.core.vehicle.VehicleSignalSource

/** AAOS Car 서비스 연결을 공유한다(기어·UX 제한·주행거리 대체 경로). */
class CarConnection(context: Context) {
    private val appContext = context.applicationContext
    private var car: Car? = null
    var error: Throwable? = null
        private set

    fun car(): Car? {
        car?.let { return it }
        return try {
            Car.createCar(appContext).also { car = it }
        } catch (t: Throwable) {
            error = t
            null
        }
    }

    fun propertyManager(): CarPropertyManager? =
        runCatching { car()?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }
            .onFailure { error = it }.getOrNull()

    fun uxManager(): CarUxRestrictionsManager? =
        runCatching { car()?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager }
            .onFailure { error = it }.getOrNull()

    fun release() {
        runCatching { car?.disconnect() }
        car = null
    }
}

/** AAOS VehicleGear 값 → 앱 기어. 수동 1~9단은 전진(DRIVE)으로 본다. */
fun gearFromAaos(value: Int): Gear = when (value) {
    VehicleGear.GEAR_PARK -> Gear.PARK
    VehicleGear.GEAR_REVERSE -> Gear.REVERSE
    VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
    VehicleGear.GEAR_DRIVE -> Gear.DRIVE
    VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
    else -> if (value >= VehicleGear.GEAR_FIRST) Gear.DRIVE else Gear.UNKNOWN
}

/**
 * 기어 = AAOS CarPropertyManager GEAR_SELECTION (Pleos 공개 SDK 카탈로그에 기어 없음 → 표준 AAOS 사용).
 * 권한 android.car.permission.CAR_POWERTRAIN.
 */
class CarGearSource(
    private val connection: CarConnection,
    private val propertyId: Int = VehiclePropertyIds.GEAR_SELECTION,
) : VehicleSignalSource {
    override val name = "aaos-gear"

    private var sink: VehicleEventListener? = null
    private var manager: CarPropertyManager? = null

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyId != propertyId) return
            (value.value as? Int)?.let { sink?.onVehicleEvent(VehicleEvent.GearChanged(gearFromAaos(it), now())) }
        }

        override fun onErrorEvent(propId: Int, zone: Int) {
            reportError("onErrorEvent prop=$propId zone=$zone")
        }
    }

    override fun start(listener: VehicleEventListener) {
        sink = MainThreadListener(listener)
        val pm = connection.propertyManager()
        if (pm == null) {
            reportError("CarPropertyManager unavailable: ${connection.error?.message}")
            return
        }
        manager = pm
        try {
            val ok = pm.registerCallback(callback, propertyId, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            if (!ok) reportError("registerCallback returned false")
        } catch (e: Exception) {
            reportError(e.message ?: e.javaClass.simpleName)
        }
    }

    override fun stop() {
        runCatching { manager?.unregisterCallback(callback) }
        manager = null
        sink = null
    }

    private fun reportError(message: String) {
        sink?.onVehicleEvent(VehicleEvent.SourceError(name, message, now()))
    }
}

/**
 * 주행거리 대체 경로 = AAOS PERF_ODOMETER(km). Pleos 이미지에서는 권한 CAR_MILEAGE가 서드파티에 부여되지 않아
 * (실측 2026-10-30) 운행 원천에서 뺐다. 다른 AAOS 기기를 위해 남겨 둔다.
 * 주 경로는 Pleos Odometer(실측 후 확정).
 */
class CarOdometerSource(private val connection: CarConnection) : VehicleSignalSource {
    override val name = "aaos-odometer"
    private var sink: VehicleEventListener? = null
    private var manager: CarPropertyManager? = null

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            (value.value as? Float)?.let { sink?.onVehicleEvent(VehicleEvent.OdometerUpdated(it.toDouble(), now())) }
        }

        override fun onErrorEvent(propId: Int, zone: Int) = Unit
    }

    override fun start(listener: VehicleEventListener) {
        sink = MainThreadListener(listener)
        val pm = connection.propertyManager() ?: return
        manager = pm
        try {
            pm.registerCallback(callback, VehiclePropertyIds.PERF_ODOMETER, 1f)
        } catch (e: Exception) {
            sink?.onVehicleEvent(VehicleEvent.SourceError(name, e.message ?: e.javaClass.simpleName, now()))
        }
    }

    override fun stop() {
        runCatching { manager?.unregisterCallback(callback) }
        manager = null
        sink = null
    }
}

/**
 * CarUxRestrictions: 주행 중 최소 정보만 보이도록 한다.
 * 서비스가 없거나 실패하면 null을 넘기고, 화면은 '기어 P가 아니면 제한'으로 보수적으로 처리한다.
 */
class UxRestrictionWatcher(private val connection: CarConnection) {
    private var manager: CarUxRestrictionsManager? = null

    fun start(onChange: (Boolean?) -> Unit) {
        val m = connection.uxManager()
        if (m == null) {
            onChange(null)
            return
        }
        manager = m
        try {
            onChange(m.currentCarUxRestrictions?.isRequiresDistractionOptimization)
            m.registerListener { restrictions ->
                onMain { onChange(restrictions.isRequiresDistractionOptimization) }
            }
        } catch (e: Exception) {
            onChange(null)
        }
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        manager = null
    }
}
