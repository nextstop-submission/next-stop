package app.nextstop.platform

import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import app.nextstop.core.vehicle.DoorUnion
import app.nextstop.core.vehicle.VehicleEvent
import app.nextstop.core.vehicle.VehicleEventListener
import app.nextstop.core.vehicle.VehicleSignalSource

/**
 * 체크포인트 문 열림/닫힘 = Pleos Vehicle SDK Door Position.
 * 공식 샘플(pleos-sample-vehicle-app)의 typed Door 패턴: capability → read → register / unregister(같은 리스너).
 * 값 해석: 0 = 닫힘, 0 초과 = 열림(에뮬레이터 capability 범위 0..1 기준, 실측으로 재확인).
 *
 * [areaNames]의 문을 [DoorUnion]으로 묶어 '하나라도 열림'을 보낸다. 기본은 1열 운전석·조수석(설정 Prefs.frontDoors) —
 * 조수석 하차·동승자 배송·우핸들. 한 문의 등록이 실패해도 나머지 문은 그대로 쓴다.
 */
class PleosDoorSource(
    private val session: PleosVehicleSession,
    areaNames: List<String> = listOf(DRIVER),
) : VehicleSignalSource {
    override val name = "pleos-door"

    private val areas = areaNames.map { it to DoorArea(DoorValue.valueOf(it)) }
    private val union = DoorUnion()
    private var sink: VehicleEventListener? = null
    // 2초 직접 읽기는 별도 스레드에서 이 맵을 읽는다
    private val registered = java.util.concurrent.ConcurrentHashMap<String, DoorPositionListener>()

    private fun listenerFor(areaName: String) = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) {
            pos?.let { onMain { emit(areaName, it) } }
        }

        override fun onFailed(exception: Exception) = reportError(exception)
    }

    private var lastSent: Boolean? = null

    /** 묶은 값이 바뀐 때만 보낸다(2초마다 다시 읽어도 같은 값은 조용히). [polled]면 콜백을 놓쳐 직접 읽기로 찾은 변화. */
    private fun emit(areaName: String, pos: Int, polled: Boolean = false) {
        val open = union.update(areaName, pos > 0)
        if (open == lastSent) return
        lastSent = open
        if (polled) android.util.Log.i("NextStop", "NS|POLL|door open=$open (콜백보다 먼저 직접 읽음)")
        sink?.onVehicleEvent(VehicleEvent.DoorChanged(open, now()))
    }

    override fun start(listener: VehicleEventListener) {
        sink = MainThreadListener(listener)
        attach(attempt = 1)
    }

    /** 서비스 바인딩이 늦을 수 있어 실패하면 1.5초 간격으로 몇 번 더 시도한다. */
    private fun attach(attempt: Int) {
        if (sink == null || registered.size == areas.size) return
        try {
            if (!session.initialize()) throw session.initError ?: IllegalStateException("Vehicle initialize failed")
        } catch (e: Exception) {
            if (attempt < MAX_ATTEMPTS) mainHandler.postDelayed({ attach(attempt + 1) }, RETRY_MS) else reportError(e)
            return
        }
        val door = session.vehicle.getDoor()
        for ((areaName, area) in areas) {
            if (registered.containsKey(areaName)) continue
            try {
                read(areaName, area)
                val l = listenerFor(areaName)
                door.registerDoorPosition(area, l)
                registered[areaName] = l
            } catch (e: Exception) {
                // 이 차(이미지)에 없는 문일 수 있다 — 운전석 문만으로도 동작한다.
                if (attempt >= MAX_ATTEMPTS) reportError(e)
            }
        }
        if (registered.size < areas.size && attempt < MAX_ATTEMPTS) mainHandler.postDelayed({ attach(attempt + 1) }, RETRY_MS)
    }

    private fun read(areaName: String, area: DoorArea, polled: Boolean = false) {
        session.vehicle.getDoor().getCurrentDoorPosition(
            area = area,
            onComplete = { pos -> pos?.let { onMain { emit(areaName, it, polled) } } },
            onFailed = { reportError(it) },
        )
    }

    /** P 진입 때 현재 문 상태를 다시 읽는다(열림 이벤트를 놓쳤어도 지금 열려 있으면 이어서 사이클이 된다). */
    override fun refresh() {
        if (sink == null) return
        areas.filter { registered.containsKey(it.first) }.forEach { (n, a) -> runCatching { read(n, a, polled = true) } }
    }

    override fun stop() {
        for ((areaName, area) in areas) {
            val l = registered[areaName] ?: continue
            runCatching { session.vehicle.getDoor().unregisterDoorPosition(area, l) }
        }
        registered.clear()
        sink = null
        lastSent = null
    }

    private fun reportError(e: Exception) {
        sink?.onVehicleEvent(VehicleEvent.SourceError(name, VehicleErrors.describe(e), now()))
    }

    companion object {
        const val DRIVER = "ROW_1_LEFT"
        val FRONT_ROW = listOf("ROW_1_LEFT", "ROW_1_RIGHT")
        private const val MAX_ATTEMPTS = 4
        private const val RETRY_MS = 1_500L
    }
}
