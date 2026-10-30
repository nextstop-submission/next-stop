package app.nextstop.platform

import app.nextstop.core.model.Stop
import app.nextstop.core.nav.Navigator
import app.nextstop.core.nav.NavigatorListener

/**
 * 목적지 전송을 [delayMs]만큼 늦춘다. requestRoute가 내비 화면을 앞으로 띄우더라도
 * '완료 → 다음 집' 연출(약 1.4초)이 먼저 보이게 하기 위함. D를 넣은 직후라 운전자는 아직 출발 전이다.
 * 대기 중 새 요청이 오면 이전 요청은 버린다(마지막 목적지만 전송).
 */
class DelayedNavigator(private val inner: Navigator, private val delayMs: Long = 1_500L) : Navigator {
    private var pending: Runnable? = null

    override fun navigateTo(stop: Stop) {
        pending?.let(mainHandler::removeCallbacks)
        val r = Runnable {
            pending = null
            inner.navigateTo(stop)
        }
        pending = r
        mainHandler.postDelayed(r, delayMs)
    }

    override fun cancel() {
        pending?.let(mainHandler::removeCallbacks)
        pending = null
        inner.cancel()
    }

    override fun setListener(listener: NavigatorListener?) = inner.setListener(listener)
}
