package app.nextstop.core.nav

import app.nextstop.core.model.Stop

/**
 * 내비 목적지 전달. 실제 구현은 Pleos NaviHelper.requestRoute(경유지 없이 1곳씩).
 * 결과는 비동기이므로 [NavigatorListener]로 받는다.
 */
interface Navigator {
    fun navigateTo(stop: Stop)
    fun cancel()
    fun setListener(listener: NavigatorListener?)
}

sealed interface NavEvent {
    data class Requested(val stopId: String) : NavEvent
    data class RoutingCompleted(val stopId: String?) : NavEvent
    data class RouteStarted(val stopId: String?) : NavEvent
    data class Arrived(val stopId: String?) : NavEvent
    data class Cancelled(val stopId: String?) : NavEvent
    data class Failed(val stopId: String?, val reason: String) : NavEvent
}

fun interface NavigatorListener {
    fun onNavEvent(event: NavEvent)
}

/** 요청을 기록만 하는 목 내비. 테스트·시연 모드용. */
class RecordingNavigator : Navigator {
    val requested = mutableListOf<Stop>()
    var cancelCount = 0
        private set
    private var listener: NavigatorListener? = null

    override fun navigateTo(stop: Stop) {
        requested += stop
        listener?.onNavEvent(NavEvent.Requested(stop.id))
    }

    override fun cancel() {
        cancelCount++
        listener?.onNavEvent(NavEvent.Cancelled(requested.lastOrNull()?.id))
    }

    override fun setListener(listener: NavigatorListener?) {
        this.listener = listener
    }
}
