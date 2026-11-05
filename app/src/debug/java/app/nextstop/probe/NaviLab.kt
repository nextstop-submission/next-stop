package app.nextstop.probe

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.ChargerOperator
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.Locale

/**
 * 내비 실험(실측 전용): adb 브로드캐스트로 NaviHelper API를 하나씩 부르고, 오는 이벤트를 자세히 기록한다.
 * 배송지 직접 추가(내비 장소 검색 → 목적지 정보)와 경로 안내 이벤트(onRouteStarted·onDestinationArrived) 실측용.
 *
 *   adb shell am start -n app.nextstop/.probe.ProbeActivity --es lab navi
 *   adb shell am broadcast -a app.nextstop.LAB --es cmd search --es keyword 서울역
 *   한글 인자는 `u:퍼센트인코딩`으로도 넘길 수 있다([AdbArg] — Windows PowerShell용).
 *   cmd: reconnect · search(keyword) · dest · loc · state · recent · bookmarks · route(lat,lng,name) · cancel · foreground · recentscreen · geocode(keyword)
 *
 * 기록 대상은 검색한 공공 장소·에뮬레이터 위치뿐이다(개인 데이터 없음).
 */
class NaviLab(private val context: Context, private val record: (String, String, String) -> Unit) {

    private var helper: NaviHelper? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val cmd = intent.getStringExtra("cmd") ?: return
            val keyword = AdbArg.text(intent.getStringExtra("keyword"))
            val name = AdbArg.text(intent.getStringExtra("name")) ?: "실측 목적지"
            record("lab", "cmd", cmd + (keyword?.let { " $it" } ?: ""))
            if (cmd == "geocode") return geocode(keyword ?: "서울역")
            if (cmd == "reconnect") return reconnect()
            val h = helper ?: return record("lab", "error", "helper not connected")
            val r = runCatching {
                when (cmd) {
                    "search" -> h.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword ?: ""))
                    "foreground" -> h.requestNavigationScreen(NavigationScreenRequest.Foreground)
                    "recentscreen" -> h.requestNavigationScreen(NavigationScreenRequest.RecentDestinations)
                    "dest" -> h.getDestinationInfo()
                    "loc" -> h.getCurrentLocationInfo()
                    "state" -> h.getRouteStateInfo()
                    "recent" -> h.getRecentDestinationInfo()
                    "bookmarks" -> h.getBookmarkInfo()
                    "cancel" -> h.cancelRoute()
                    "route" -> h.requestRoute(
                        RouteInfo(
                            latitude = intent.getStringExtra("lat")!!.toDouble(),
                            longitude = intent.getStringExtra("lng")!!.toDouble(),
                            poiId = "",
                            poiName = name,
                            poiSubId = "0",
                            address = name,
                        )
                    )
                    else -> error("unknown cmd")
                }
            }
            record("lab", "$cmd.result", r.fold({ "called" }, { "throw ${it.javaClass.simpleName} ${it.message}" }))
        }
    }

    fun start() {
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
        connect()
    }

    /** 내비가 다시 초기화(INITIALIZING)된 뒤 이벤트가 끊기는지, 새로 연결하면 돌아오는지 본다(2회차 CI: 경로 요청 뒤 이벤트 끊김). */
    private fun reconnect() {
        helper?.let { runCatching { it.removeListener(events); it.release() } }
        helper = null
        connect()
    }

    private fun connect() {
        val h = NaviHelper(context.applicationContext)
        h.initialize(
            onConnected = {
                runCatching { h.addListener(events) }
                helper = h
                record("lab", "connected", "ok")
            },
            onFailed = { record("lab", "connected", "fail ${it.message}") },
        )
    }

    /** 키 없는 Android 기본 지오코더가 이 이미지에 있는지(주소 → 좌표). Google 서비스가 없는 차량 이미지면 보통 없다. */
    private fun geocode(name: String) {
        record("lab", "geocoder.isPresent", android.location.Geocoder.isPresent().toString())
        Thread {
            val r = runCatching {
                @Suppress("DEPRECATION")
                android.location.Geocoder(context, Locale.KOREA).getFromLocationName(name, 3)
                    ?.joinToString(" | ") { "${it.featureName} ${ll(it.latitude, it.longitude)}" } ?: "null"
            }
            record("lab", "geocoder.result", r.fold({ it.ifEmpty { "empty" } }, { "throw ${it.javaClass.simpleName} ${it.message}" }))
        }.start()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        helper?.let { runCatching { it.removeListener(events); it.release() } }
        helper = null
    }

    private fun ll(lat: Double, lng: Double) = String.format(Locale.ROOT, "%.6f,%.6f", lat, lng)

    private val events = object : NaviHelperEventListener {
        private fun ev(name: String, detail: String = "") = record("lab.event", name, detail)

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) =
            ev("onRouteStarted", "name=${routeStartInfo.name} distance=${routeStartInfo.distance} duration=${routeStartInfo.duration}")

        @Deprecated("Deprecated by NaviHelperEventListener")
        override fun onRouteEnded() = ev("onRouteEnded")
        override fun onRoutingCompleted() = ev("onRoutingCompleted")
        override fun onReRouteEnded() = ev("onReRouteEnded")
        override fun onRouteOptionChanged(routeOption: RouteOption) = ev("onRouteOptionChanged", "$routeOption")
        override fun onRouteCancelled() = ev("onRouteCancelled")
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = ev("onWaypointChanged")
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) =
            ev("onDestinationArrived", "name=${destinationArrivedInfo.name} distance=${destinationArrivedInfo.distance} duration=${destinationArrivedInfo.duration}")
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = ev("onWayPointArrived")
        override fun onError(errorCode: NaviErrorCode) = ev("onError", "$errorCode")
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) =
            ev("onCurrentLocationInfo", "at=${ll(currentLocationInfo.latitude, currentLocationInfo.longitude)} heading=${currentLocationInfo.heading}")
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = ev("onRouteStateInfo", "$routeStateInfo".take(300))
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = ev(
            "onDestinationInfo",
            "name=${destinationInfo.name} at=${ll(destinationInfo.latitude, destinationInfo.longitude)} poiId=${destinationInfo.poiId} " +
                "poiSubId=${destinationInfo.poiSubId} provider=${destinationInfo.poiProvider} remain=${destinationInfo.remainDistance}m",
        )
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = ev("onWayPointInfo", "${waypointInfoList.size}")
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) = ev(
            "onRecentDestinationInfo",
            recentDestinationInfoList.take(5).joinToString(" | ") { "${it.name} ${ll(it.latitude, it.longitude)} poi=${it.poiId}" },
        )
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) = ev(
            "onBookmarkInfo",
            bookmarkInfoList.take(5).joinToString(" | ") { "${it.name} ${ll(it.latitude, it.longitude)} poi=${it.poiId} ${it.category}" },
        )
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = Unit
        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) = Unit
        override fun onDrivingInfo(drivingInfo: DrivingInfo) = ev("onDrivingInfo", "$drivingInfo".take(200))
        override fun onBookMarkChanged() = ev("onBookMarkChanged")
        override fun onDestinationChanged() = ev("onDestinationChanged")
        override fun onNavigationStatus(status: NavigationStatus) = ev("onNavigationStatus", "$status")
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = Unit
        override fun onChargerOperatorInfo(chargerOperatorList: List<ChargerOperator>) = Unit
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) = Unit
        override fun onTripInfo(tripInfo: TripInfo) = ev("onTripInfo")
    }

    companion object {
        const val ACTION = "app.nextstop.LAB"
    }
}
