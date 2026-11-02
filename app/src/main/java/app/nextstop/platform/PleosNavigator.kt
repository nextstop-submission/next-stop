package app.nextstop.platform

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
import android.content.Context
import android.util.Log
import app.nextstop.core.model.DestinationCandidate
import app.nextstop.core.model.Stop
import app.nextstop.core.nav.NavEvent
import app.nextstop.core.nav.Navigator
import app.nextstop.core.nav.NavigatorListener

/**
 * 내비 목적지 = Pleos NaviHelper.requestRoute(RouteInfo).
 * 경유지 없이 '다음 배송지 1곳'만 목적지로 보낸다. 다시 requestRoute하면 목적지가 교체된다(공식 문서).
 * requestRoute 성공 ≠ 경로 탐색 완료. 결과는 리스너(onRoutingCompleted/onError)로 확인한다.
 */
class PleosNavigator(context: Context) : Navigator {
    private val appContext = context.applicationContext
    private var helper: NaviHelper? = null
    private var listener: NavigatorListener? = null
    private var currentStopId: String? = null
    var lastError: String? = null
        private set

    /** 실측 화면에서 조회 API를 직접 부르기 위한 접근자. */
    internal val helperOrNull: NaviHelper? get() = helper

    /** 목적지 이름 외 POI 정보가 없을 때 쓰는 값. 빈 poiId 허용 여부는 실측(ProbeActivity) 대상. */
    var poiIdForCoordinates: String = ""

    /** 서비스 연결 상태. 공식 문서 권장대로 initialize(onConnected, onFailed)의 결과로 바뀐다. */
    enum class Connection { IDLE, CONNECTING, CONNECTED, FAILED }

    var connection = Connection.IDLE
        private set

    /** 연결 전에 들어온 요청. 연결되면 순서대로 실행하고, 실패하면 각 요청의 실패 처리로 넘긴다. */
    private val pending = ArrayDeque<Pair<(NaviHelper) -> Unit, (String) -> Unit>>()

    /** 콜백으로 연결 결과를 받는 초기화(공식 문서 권장). 이미 시작했으면 아무것도 하지 않는다. */
    fun initialize(): Boolean {
        if (connection == Connection.CONNECTING || connection == Connection.CONNECTED) return true
        var h: NaviHelper? = null
        return try {
            h = NaviHelper(appContext)
            helper = h
            connection = Connection.CONNECTING
            h.initialize(
                onConnected = {
                    onMain {
                        // 문서: 첫 연결이 성공한 뒤 리스너를 등록하고 API를 호출한다.
                        runCatching { h.addListener(events) }
                        connection = Connection.CONNECTED
                        rawListener?.invoke("initialize.onConnected")
                        drainPending()
                    }
                },
                onFailed = { t ->
                    onMain {
                        connection = Connection.FAILED
                        lastError = t.message ?: t.javaClass.simpleName
                        rawListener?.invoke("initialize.onFailed $lastError")
                        failPending("NaviHelper 연결 실패: $lastError")
                    }
                },
            )
            true
        } catch (e: Exception) {
            runCatching { h?.release() }
            helper = null
            connection = Connection.FAILED
            lastError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    fun release() {
        val h = helper ?: return
        runCatching {
            if (connection == Connection.CONNECTED) h.removeListener(events)
            h.release()
        }
        helper = null
        connection = Connection.IDLE
        failPending("released")
    }

    override fun setListener(listener: NavigatorListener?) {
        this.listener = listener
    }

    /** 연결 전에 들어온 마지막 목적지. 목적지는 마지막 것만 의미가 있으므로 하나만 보관한다. */
    private var pendingStop: Stop? = null

    override fun navigateTo(stop: Stop) {
        currentStopId = stop.id
        val alreadyWaiting = pendingStop != null
        pendingStop = stop
        if (alreadyWaiting && connection == Connection.CONNECTING) return
        whenConnected(
            call = { h ->
                val target = pendingStop ?: return@whenConnected
                pendingStop = null
                h.requestRoute(routeInfoFor(target))
                emit(NavEvent.Requested(target.id))
            },
            onFail = { reason ->
                pendingStop = null
                emit(NavEvent.Failed(stop.id, reason))
            },
        )
    }

    override fun cancel() {
        // 누가 경로를 취소했는지 실측에서 가리려고 부른 쪽을 남긴다(내비 7회차: 취소 주체를 기록만으로 못 가렸다)
        val by = Throwable().stackTrace.drop(1).take(2).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
        Log.i("NextStop", "NS|NAV|cancelRoute by=$by connected=${connection == Connection.CONNECTED}")
        if (connection == Connection.CONNECTED) runCatching { helper?.cancelRoute() }
    }

    /** 내비에 저장된 최근 목적지·즐겨찾기를 배송지 후보로 받는다. 결과는 [onDestinations]로 비동기 전달. */
    var onDestinations: ((source: String, candidates: List<DestinationCandidate>) -> Unit)? = null

    fun requestRecentDestinations(): Boolean = whenConnected(call = { it.getRecentDestinationInfo() }, onFail = { lastError = it })

    fun requestBookmarks(): Boolean = whenConnected(call = { it.getBookmarkInfo() }, onFail = { lastError = it })

    /**
     * 배송지 직접 추가 1단계: 내비의 장소 검색 화면을 [keyword]로 연다(NAVI_CUSTOM_MAP).
     * 사용자가 결과를 골라 목적지로 정하면 [onDestinationChanged]가 오고, [requestDestinationInfo]로 좌표·POI를 받는다.
     */
    fun searchPlace(keyword: String): Boolean =
        whenConnected(call = { it.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword)) }, onFail = { lastError = it })

    /** 내비 화면을 앞으로 띄운다(검색 화면 요청이 안 되는 환경에서 사용자가 내비에서 직접 고르게). */
    fun openNavi(): Boolean =
        whenConnected(call = { it.requestNavigationScreen(NavigationScreenRequest.Foreground) }, onFail = { lastError = it })

    /** 내비에 지금 설정된 목적지. 결과는 [onDestination]으로 비동기 전달. */
    fun requestDestinationInfo(): Boolean = whenConnected(call = { it.getDestinationInfo() }, onFail = { lastError = it })

    /** 내비 목적지가 바뀌었다(검색 결과를 목적지로 정함, 또는 다른 앱·사람이 바꿈). */
    var onDestinationChanged: (() -> Unit)? = null

    /**
     * 내비 경로 안내가 시작됐다. 검색 결과에서 '목적지로'를 누르면 [onDestinationChanged] 없이 이것만 온다
     * (CI 내비 7회차 `ci-20261102-1407`: onRouteStarted(서울역 KTX) → onDestinationChanged 0회).
     */
    var onRouteStarted: (() -> Unit)? = null

    /** [requestDestinationInfo] 결과. 목적지가 없으면 좌표가 0일 수 있다(호출 쪽이 거른다). */
    var onDestination: ((DestinationCandidate) -> Unit)? = null

    /** 연결돼 있으면 바로, 연결 중이면 연결 뒤에 실행한다. 연결할 수 없으면 false. */
    private fun whenConnected(call: (NaviHelper) -> Unit, onFail: (String) -> Unit): Boolean {
        if (connection == Connection.IDLE || connection == Connection.FAILED) {
            if (!initialize()) {
                onFail("NaviHelper init: $lastError")
                return false
            }
        }
        val h = helper
        if (connection == Connection.CONNECTED && h != null) {
            runCatching { call(h) }.onFailure { onFail(it.message ?: it.javaClass.simpleName) }
        } else {
            pending.addLast(call to onFail)
        }
        return true
    }

    private fun drainPending() {
        val h = helper ?: return
        while (pending.isNotEmpty()) {
            val (call, onFail) = pending.removeFirst()
            runCatching { call(h) }.onFailure { onFail(it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun failPending(reason: String) {
        while (pending.isNotEmpty()) pending.removeFirst().second(reason)
    }

    /** 내비에서 가져온 배송지면 그 POI ID로, 샘플 좌표면 좌표만으로 요청한다. */
    fun routeInfoFor(stop: Stop) = RouteInfo(
        latitude = stop.location.latitude,
        longitude = stop.location.longitude,
        poiId = stop.poiId ?: poiIdForCoordinates,
        poiName = stop.label,
        poiSubId = stop.poiSubId ?: "0",
        address = stop.label,
    )

    private fun deliverDestinations(source: String, list: List<DestinationCandidate>) =
        onMain { onDestinations?.invoke(source, list) }

    private fun emit(event: NavEvent) = onMain { listener?.onNavEvent(event) }

    /**
     * NaviHelperEventListener 전체 콜백. 배송 흐름에 필요한 것만 NavEvent로 바꾸고 나머지는 [rawListener]로 흘린다.
     * 실측 로그가 저장소에 남으므로 위치·주소가 담긴 객체는 문자열로 찍지 않는다(이벤트 이름·개수만).
     */
    var rawListener: ((String) -> Unit)? = null

    private val events = object : NaviHelperEventListener {
        private fun raw(s: String) {
            rawListener?.let { l -> onMain { l(s) } }
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            raw("onRouteStarted"); emit(NavEvent.RouteStarted(currentStopId))
            onMain { onRouteStarted?.invoke() }
        }

        @Deprecated("Deprecated by NaviHelperEventListener")
        override fun onRouteEnded() = raw("onRouteEnded")

        override fun onRoutingCompleted() {
            raw("onRoutingCompleted"); emit(NavEvent.RoutingCompleted(currentStopId))
        }

        override fun onReRouteEnded() = raw("onReRouteEnded")
        override fun onRouteOptionChanged(routeOption: RouteOption) = raw("onRouteOptionChanged $routeOption")

        override fun onRouteCancelled() {
            raw("onRouteCancelled"); emit(NavEvent.Cancelled(currentStopId))
        }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = raw("onWaypointChanged")

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            raw("onDestinationArrived"); emit(NavEvent.Arrived(currentStopId))
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = raw("onWayPointArrived")

        override fun onError(errorCode: NaviErrorCode) {
            raw("onError $errorCode"); emit(NavEvent.Failed(currentStopId, errorCode.toString()))
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = raw("onCurrentLocationInfo")
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = raw("onRouteStateInfo")
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            raw("onDestinationInfo")
            val c = with(destinationInfo) { DestinationCandidate(name, address, latitude, longitude, poiId, poiSubId) }
            onMain { onDestination?.invoke(c) }
        }
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = raw("onWayPointInfo ${waypointInfoList.size}")
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
            raw("onRecentDestinationInfo ${recentDestinationInfoList.size}")
            deliverDestinations("recent", recentDestinationInfoList.map {
                DestinationCandidate(it.name, it.address, it.latitude, it.longitude, it.poiId, it.poiSubId)
            })
        }

        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            raw("onBookmarkInfo ${bookmarkInfoList.size}")
            deliverDestinations("bookmark", bookmarkInfoList.map {
                DestinationCandidate(it.name, it.address, it.latitude, it.longitude, it.poiId, it.poiSubId)
            })
        }
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = Unit
        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) = Unit
        override fun onDrivingInfo(drivingInfo: DrivingInfo) = raw("onDrivingInfo")
        override fun onBookMarkChanged() = raw("onBookMarkChanged")
        override fun onDestinationChanged() {
            raw("onDestinationChanged"); onMain { onDestinationChanged?.invoke() }
        }
        override fun onNavigationStatus(status: NavigationStatus) = raw("onNavigationStatus $status")
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = Unit
        override fun onChargerOperatorInfo(chargerOperatorList: List<ChargerOperator>) = Unit
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) = Unit
        override fun onTripInfo(tripInfo: TripInfo) = raw("onTripInfo")
    }
}
