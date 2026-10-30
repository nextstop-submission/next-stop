package app.nextstop.platform

import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import app.nextstop.core.vehicle.VehicleEvent
import app.nextstop.core.vehicle.VehicleEventListener
import app.nextstop.core.vehicle.VehicleSignalSource

/**
 * 운전석 도어 열림/닫힘 = Pleos Vehicle SDK Door Position.
 * 공식 샘플(pleos-sample-vehicle-app)의 typed Door 패턴: capability → read → register / unregister(같은 리스너).
 * 값 해석: 0 = 닫힘, 0 초과 = 열림(에뮬레이터 capability 범위 0..1 기준, 실측으로 재확인).
 */
class PleosDoorSource(
    private val session: PleosVehicleSession,
    areaName: String = DEFAULT_AREA,
) : VehicleSignalSource {
    override val name = "pleos-door"

    private val area = DoorArea(DoorValue.valueOf(areaName))
    private var sink: VehicleEventListener? = null
    private var registered = false

    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(pos: Int?) {
            pos?.let { sink?.onVehicleEvent(VehicleEvent.DoorChanged(it > 0, now())) }
        }

        override fun onFailed(exception: Exception) = reportError(exception)
    }

    override fun start(listener: VehicleEventListener) {
        sink = MainThreadListener(listener)
        attach(attempt = 1)
    }

    /** 서비스 바인딩이 늦을 수 있어 실패하면 1.5초 간격으로 몇 번 더 시도한다. */
    private fun attach(attempt: Int) {
        if (sink == null || registered) return
        try {
            if (!session.initialize()) throw session.initError ?: IllegalStateException("Vehicle initialize failed")
            val door = session.vehicle.getDoor()
            door.getCurrentDoorPosition(
                area = area,
                onComplete = { pos -> pos?.let { sink?.onVehicleEvent(VehicleEvent.DoorChanged(it > 0, now())) } },
                onFailed = { reportError(it) },
            )
            door.registerDoorPosition(area, doorListener)
            registered = true
        } catch (e: Exception) {
            if (attempt < MAX_ATTEMPTS) mainHandler.postDelayed({ attach(attempt + 1) }, RETRY_MS)
            else reportError(e)
        }
    }

    override fun stop() {
        if (registered) {
            runCatching { session.vehicle.getDoor().unregisterDoorPosition(area, doorListener) }
            registered = false
        }
        sink = null
    }

    private fun reportError(e: Exception) {
        sink?.onVehicleEvent(VehicleEvent.SourceError(name, VehicleErrors.describe(e), now()))
    }

    companion object {
        const val DEFAULT_AREA = "ROW_1_LEFT"
        private const val MAX_ATTEMPTS = 4
        private const val RETRY_MS = 1_500L
    }
}
