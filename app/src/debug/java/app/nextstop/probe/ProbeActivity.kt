package app.nextstop.probe

import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import android.app.Activity
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.nextstop.BuildConfig
import app.nextstop.core.sample.SampleRoutes
import app.nextstop.drive.Palette
import app.nextstop.drive.column
import app.nextstop.drive.dp
import app.nextstop.drive.label
import app.nextstop.drive.pill
import app.nextstop.drive.row
import app.nextstop.platform.CarConnection
import app.nextstop.platform.PleosNavigator
import app.nextstop.platform.PleosVehicleSession
import app.nextstop.platform.Reflective
import app.nextstop.platform.UxRestrictionWatcher
import app.nextstop.platform.gearFromAaos
import app.nextstop.platform.mainHandler
import app.nextstop.platform.onMain
import org.json.JSONObject
import java.io.File

/**
 * 실측 화면. 에뮬레이터/실차에서 실제로 무엇이 동작하는지 기록한다.
 *
 * 모든 결과는 logcat `NSPROBE` 태그(`NSPROBE|구역|키|값`)와
 * 앱 외부 파일 `probe-events.jsonl`에 남는다. 실측 스크립트(scripts/probe)가 이것을 수집해 표로 만든다.
 *
 *   adb shell am start -n app.nextstop/.probe.ProbeActivity --ez auto true
 *
 * 리스너는 화면이 살아 있는 동안 계속 등록돼 있어, Virtual Console·VHAL 주입으로 바꾼 값이 그대로 찍힌다.
 */
class ProbeActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var out: File

    private val session by lazy { PleosVehicleSession(this) }
    private val car by lazy { CarConnection(this) }
    private var navi: NaviHelper? = null
    private var naviWrapper: PleosNavigator? = null
    private var ux: UxRestrictionWatcher? = null
    private val doorListeners = mutableListOf<Pair<DoorArea, DoorPositionListener>>()
    private val odometerRegistrations = mutableListOf<Pair<String, Any>>()
    private var gearCallback: CarPropertyManager.CarPropertyEventCallback? = null
    private var lab: NaviLab? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = File(getExternalFilesDir(null), "probe-events.jsonl").also { if (!intent.getBooleanExtra("append", false)) it.delete() }
        buildUi()
        record("env", "start", "auto=${intent.getBooleanExtra("auto", false)}")
        if (intent.getStringExtra("lab") == "navi") lab = NaviLab(this, ::record).also { it.start() }
        if (intent.getBooleanExtra("auto", false)) runAll()
    }

    override fun onDestroy() {
        doorListeners.forEach { (area, l) -> runCatching { session.vehicle.getDoor().unregisterDoorPosition(area, l) } }
        odometerRegistrations.forEach { (name, proxy) ->
            runCatching {
                val odo = session.vehicle.getOdometer()
                odo.javaClass.methods.firstOrNull { it.name == "un$name" && it.parameterTypes.size == 1 }?.invoke(odo, proxy)
            }
        }
        gearCallback?.let { cb -> runCatching { car.propertyManager()?.unregisterCallback(cb) } }
        ux?.stop()
        naviWrapper?.release()
        lab?.stop()
        session.release()
        car.release()
        super.onDestroy()
    }

    private fun runAll() {
        val steps: List<Pair<Long, () -> Unit>> = listOf(
            0L to ::probeEnv,
            500L to ::probeVehicleInit,
            1_500L to ::probeDoor,
            3_000L to ::probeGear,
            4_000L to ::probeOdometer,
            6_000L to ::probeUx,
            7_000L to ::probeNaviInit,
            9_000L to { probeRoute("coords-empty-poi", poiId = "") },
            19_000L to { probeRoute("coords-poi-0", poiId = "0") },
            29_000L to ::probeNaviQueries,
            34_000L to ::probeRouteViaAppNavigator,
            44_000L to { record("env", "auto_done", "listeners stay registered") },
        )
        steps.forEach { (delay, step) -> mainHandler.postDelayed({ safely(step) }, delay) }
    }

    private fun safely(step: () -> Unit) {
        try {
            step()
        } catch (t: Throwable) {
            record("error", "uncaught", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- 항목별 실측

    private fun probeEnv() {
        record("env", "device", "${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}")
        record("env", "app_sdk", "vehicle=${BuildConfig.PLEOS_VEHICLE_SDK} navihelper=${BuildConfig.PLEOS_NAVIHELPER_SDK}")
        record("env", "vehicle_service", packageVersion(VEHICLE_SERVICE))
        record("env", "car_feature", packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE).toString())
    }

    private fun probeVehicleInit() {
        val ok = session.initialize()
        record("vehicle", "initialize", if (ok) "ok" else "fail ${session.initError}")
        if (!ok) return
        record("vehicle", "methods", Reflective.describe(session.vehicle).joinToString(" ; ") { it.name })
    }

    private fun probeDoor() {
        if (!session.initialize()) return
        val door = session.vehicle.getDoor()
        record("door", "methods", Reflective.describe(door).joinToString(" ; ") { it.name })
        door.checkDoorPositionCapabilities(
            onComplete = { config, bounds ->
                record("door", "capability", "access=${config.accessType} bounds=${bounds.entries.joinToString { (a, b) -> "${a.values}=${b.minValue}..${b.maxValue}" }}")
            },
            onFailed = { record("door", "capability", "fail ${it.message}") },
        )
        DoorValue.entries.forEach { value ->
            val area = DoorArea(value)
            door.getCurrentDoorPosition(
                area = area,
                onComplete = { pos -> record("door", "read.${value.name}", pos.toString()) },
                onFailed = { record("door", "read.${value.name}", "fail ${it.message}") },
            )
            val listener = object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) = record("door", "update.${value.name}", pos.toString())
                override fun onFailed(exception: Exception) = record("door", "update.${value.name}", "fail ${exception.message}")
            }
            runCatching { door.registerDoorPosition(area, listener) }
                .onSuccess { doorListeners += area to listener }
                .onFailure { record("door", "register.${value.name}", "fail ${it.message}") }
        }
    }

    private fun probeGear() {
        val pm = car.propertyManager()
        if (pm == null) {
            record("gear", "property_manager", "unavailable ${car.error}")
            return
        }
        listOf(
            "GEAR_SELECTION" to VehiclePropertyIds.GEAR_SELECTION,
            "CURRENT_GEAR" to VehiclePropertyIds.CURRENT_GEAR,
        ).forEach { (name, id) ->
            record("gear", "config.$name", runCatching { pm.getCarPropertyConfig(id)?.toString() ?: "null" }.getOrElse { "fail ${it.message}" })
            record("gear", "read.$name", runCatching {
                val v = pm.getIntProperty(id, 0)
                "$v (${gearFromAaos(v)})"
            }.getOrElse { "fail ${it.javaClass.simpleName} ${it.message}" })
        }
        record("odometer", "aaos.read.PERF_ODOMETER", runCatching { pm.getFloatProperty(VehiclePropertyIds.PERF_ODOMETER, 0).toString() }
            .getOrElse { "fail ${it.javaClass.simpleName} ${it.message}" })

        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                val name = if (value.propertyId == VehiclePropertyIds.GEAR_SELECTION) "GEAR_SELECTION" else "CURRENT_GEAR"
                val v = value.value as? Int
                record("gear", "update.$name", "$v (${v?.let(::gearFromAaos)})")
            }

            override fun onErrorEvent(propId: Int, zone: Int) = record("gear", "error", "prop=$propId zone=$zone")
        }
        gearCallback = cb
        listOf(VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.CURRENT_GEAR).forEach { id ->
            record("gear", "register.$id", runCatching { pm.registerCallback(cb, id, CarPropertyManager.SENSOR_RATE_ONCHANGE).toString() }
                .getOrElse { "fail ${it.javaClass.simpleName} ${it.message}" })
        }
    }

    /** Odometer: 메서드 목록을 남기고, 콜백만 받는 check/get과 리스너만 받는 register를 전부 호출해 본다. */
    private fun probeOdometer() {
        if (!session.initialize()) return
        val odo = runCatching { session.vehicle.getOdometer() }.getOrElse {
            record("odometer", "get", "fail ${it.message}")
            return
        }
        record("odometer", "methods", Reflective.describe(odo).joinToString(" ; ") { it.toString() })
        odo.javaClass.methods.filter { (it.name.startsWith("check") || it.name.startsWith("get") || it.name.startsWith("is")) && Reflective.isCallbackOnly(it) }
            .sortedBy { it.name }
            .forEach { m ->
                val err = Reflective.invokeWithCallbacks(odo, m) { idx, values ->
                    record("odometer", "call.${m.name}.cb$idx", values.joinToString { describeValue(it) })
                }
                if (err != null) record("odometer", "call.${m.name}", "throw ${err.javaClass.simpleName} ${err.message}")
            }
        odo.javaClass.methods.filter { it.name.startsWith("register") && Reflective.isListenerOnly(it) }
            .forEach { m ->
                val proxy = Reflective.proxyListener(m.parameterTypes[0]) { method, args ->
                    record("odometer", "update.${m.name}.$method", args.joinToString { describeValue(it) })
                }
                runCatching { m.invoke(odo, proxy) }
                    .onSuccess { odometerRegistrations += m.name to proxy; record("odometer", "register.${m.name}", "ok") }
                    .onFailure { record("odometer", "register.${m.name}", "fail ${it.cause?.message ?: it.message}") }
            }
    }

    private fun probeUx() {
        ux = UxRestrictionWatcher(car).also { w -> w.start { record("ux", "requiresDistractionOptimization", it.toString()) } }
    }

    private fun probeNaviInit() {
        val wrapper = PleosNavigator(this)
        wrapper.rawListener = { record("navi", "event", it) }
        val ok = wrapper.initialize()
        record("navi", "initialize", if (ok) "ok" else "fail ${wrapper.lastError}")
        naviWrapper = wrapper
        navi = wrapper.helperOrNull
    }

    private fun probeRoute(variant: String, poiId: String) {
        record("navi", "connection", naviWrapper?.connection.toString())
        val h = navi ?: return record("navi", "requestRoute.$variant", "skip (no helper)")
        val s = SampleRoutes.DEMO_5.first()
        val info = RouteInfo(
            latitude = s.location.latitude,
            longitude = s.location.longitude,
            poiId = poiId,
            poiName = s.label,
            poiSubId = "0",
            address = s.label,
        )
        record("navi", "requestRoute.$variant", runCatching { h.requestRoute(info); "called" }
            .getOrElse { "throw ${it.javaClass.simpleName} ${it.message}" })
    }

    private fun probeNaviQueries() {
        val h = navi ?: return
        record("navi", "getDestinationInfo", runCatching { h.getDestinationInfo(); "called" }.getOrElse { "throw ${it.message}" })
        record("navi", "getRouteStateInfo", runCatching { h.getRouteStateInfo(); "called" }.getOrElse { "throw ${it.message}" })
        record("navi", "getCurrentLocationInfo", runCatching { h.getCurrentLocationInfo(); "called" }.getOrElse { "throw ${it.message}" })
        naviWrapper?.onDestinations = { source, list ->
            // 개인 이동 기록이 저장소에 남지 않도록 좌표·이름은 기록하지 않는다(개수와 POI 유무만).
            record("navi", "destinations.$source", "count=${list.size} withPoi=${list.count { !it.poiId.isNullOrBlank() }}")
        }
        record("navi", "getRecentDestinationInfo", runCatching { h.getRecentDestinationInfo(); "called" }.getOrElse { "throw ${it.message}" })
        record("navi", "getBookmarkInfo", runCatching { h.getBookmarkInfo(); "called" }.getOrElse { "throw ${it.message}" })
    }

    /** 앱 본 경로(PleosNavigator.navigateTo)로 두 번째 배송지를 보내 목적지 교체가 되는지 본다. */
    private fun probeRouteViaAppNavigator() {
        val w = naviWrapper ?: return
        w.setListener { record("navi", "app_nav_event", it.toString()) }
        w.navigateTo(SampleRoutes.DEMO_5[1])
        record("navi", "app_navigateTo", SampleRoutes.DEMO_5[1].id)
    }

    // ---------------------------------------------------------------- 기록

    private fun record(section: String, key: String, value: String) {
        val line = "NSPROBE|$section|$key|$value"
        Log.i(TAG, line)
        runCatching {
            out.appendText(JSONObject().put("t", System.currentTimeMillis()).put("section", section).put("key", key).put("value", value).toString() + "\n")
        }
        onMain {
            logView.append("[$section] $key = $value\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun describeValue(v: Any?): String = when (v) {
        null -> "null"
        is Throwable -> "${v.javaClass.simpleName}: ${v.message}"
        is Map<*, *> -> v.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}=${it.value}" }
        else -> "$v<${v.javaClass.simpleName}>"
    }

    private fun packageVersion(pkg: String): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    }.getOrElse { "not visible (${it.javaClass.simpleName})" }

    private fun buildUi() {
        logView = label(18f, Palette.TEXT).apply {
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(logView) }
        val buttons = row(
            pill("전체 자동", filled = true) { runAll() },
            pill("도어") { safely(::probeDoor) },
            pill("기어") { safely(::probeGear) },
            pill("Odometer") { safely(::probeOdometer) },
            pill("내비 목적지") { safely { if (navi == null) probeNaviInit(); probeRoute("manual", "") } },
            spacingDp = 12f,
        )
        val root = column().apply {
            setBackgroundColor(Palette.BG)
            setPadding(dp(32f), dp(24f), dp(32f), dp(24f))
            addView(label(28f, Palette.SUB).apply { text = "실측 — 결과는 logcat NSPROBE · probe-events.jsonl" })
            addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16f) })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16f) })
        }
        setContentView(root)
    }

    companion object {
        const val TAG = "NSPROBE"
        const val VEHICLE_SERVICE = "ai.pleos.playground.service.vehicle"
    }
}
