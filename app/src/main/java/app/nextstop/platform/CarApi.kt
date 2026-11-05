package app.nextstop.platform

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
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

    /** 붙은 Car가 쓸모없을 때(관리자를 못 얻음) 다음 호출에서 새로 만들게 한다. */
    fun reset() = release()
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
 * 기어 = AAOS CarPropertyManager GEAR_SELECTION (Pleos SDK의 Gear는 2.3.2에서 deprecated, 경고가 GEAR_SELECTION을 안내).
 * 권한 android.car.permission.CAR_POWERTRAIN.
 *
 * 콜백만 믿지 않는다: 11/4 CI 리허설(ci-20261104-0206)에서 Virtual Console의 P·D 변경 콜백이 앱에 끝내 오지 않은 일이 있었다.
 * [refresh]는 현재 값을 직접 읽는다(RunStore가 2초마다 부름). 콜백과 직접 읽기는 VHAL 시각(timestamp)으로 줄 세워
 * 늦게 온 옛 값이 새 값을 덮지 않게 하고, 값이 바뀐 때만 보낸다.
 */
class CarGearSource(
    private val connection: CarConnection,
    private val propertyId: Int = VehiclePropertyIds.GEAR_SELECTION,
) : VehicleSignalSource {
    override val name = "aaos-gear"

    @Volatile private var sink: VehicleEventListener? = null
    @Volatile private var manager: CarPropertyManager? = null

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyId != propertyId) return
            deliver(value, polled = false)
        }

        override fun onErrorEvent(propId: Int, zone: Int) {
            reportError("onErrorEvent prop=$propId zone=$zone")
        }
    }

    override fun start(listener: VehicleEventListener) {
        sink = MainThreadListener(listener)
        if (!attach()) reportError("CarPropertyManager unavailable: ${connection.error?.message} (2초마다 다시 붙음)")
    }

    /**
     * Car 서비스에 붙는다. 앱 프로세스가 기기 부하로 다시 시작될 때 Car 서비스가 아직 준비 안 돼 실패할 수 있다
     * (11/5 CI 리허설 ci-20261105-2214-3c89fb0: 재시작 뒤 기어를 끝내 못 받아 C 0/5). 실패하면 [refresh](2초마다)가 다시 붙는다.
     */
    @Synchronized
    private fun attach(): Boolean {
        if (manager != null) return true
        val pm = connection.propertyManager()
        if (pm == null) {
            connection.reset()
            return false
        }
        return try {
            val ok = pm.registerCallback(callback, propertyId, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            if (ok) {
                manager = pm
                Log.i("NextStop", "NS|GEAR|attached")
            }
            ok
        } catch (e: Exception) {
            reportError(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private var lastTs = Long.MIN_VALUE
    private var lastValue: Int? = null

    private fun deliver(value: CarPropertyValue<*>, polled: Boolean) {
        val v = value.value as? Int ?: return
        val emit = synchronized(this) {
            if (value.timestamp < lastTs) return // 늦게 온 옛 값
            lastTs = value.timestamp
            val changed = v != lastValue
            lastValue = v
            changed
        }
        if (!emit) return
        if (polled) Log.i("NextStop", "NS|POLL|gear=${gearFromAaos(v)} (콜백보다 먼저 직접 읽음)")
        sink?.onVehicleEvent(VehicleEvent.GearChanged(gearFromAaos(v), now()))
    }

    override fun refresh() {
        if (sink == null) return
        val pm = manager ?: run { attach(); manager } ?: return
        runCatching { pm.getProperty<Int>(propertyId, 0) }.getOrNull()?.let { deliver(it, polled = true) }
    }

    override fun stop() {
        runCatching { manager?.unregisterCallback(callback) }
        manager = null
        sink = null
        synchronized(this) { lastTs = Long.MIN_VALUE; lastValue = null }
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
