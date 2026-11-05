package app.nextstop.core.vehicle

/** 체크포인트 판정에 쓰는 기어 값. 원천(AAOS VehicleGear 등)과 무관한 앱 내부 표현. */
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 차량 신호 이벤트. 실제 SDK·VHAL·목(mock) 어느 쪽에서 오든 이 형태로 통일한다. */
sealed interface VehicleEvent {
    val atMillis: Long

    data class GearChanged(val gear: Gear, override val atMillis: Long) : VehicleEvent

    /** 체크포인트 대상 문(기본: 1열 운전석·조수석을 [DoorUnion]으로 묶은 값) 열림/닫힘. */
    data class DoorChanged(val open: Boolean, override val atMillis: Long) : VehicleEvent

    /** 누적 주행거리(km). */
    data class OdometerUpdated(val km: Double, override val atMillis: Long) : VehicleEvent

    /** 원천 쪽 오류(권한·미지원 등). 상태기계는 무시하고 화면·로그에만 남긴다. */
    data class SourceError(val source: String, val message: String, override val atMillis: Long) : VehicleEvent
}

fun interface VehicleEventListener {
    fun onVehicleEvent(event: VehicleEvent)
}

/**
 * 차량 신호 원천. 구현체를 바꿔 끼우는 것만으로 실차/에뮬레이터/목을 전환한다.
 * start 이후 받은 이벤트는 호출 스레드와 무관하게 listener로 전달된다.
 */
interface VehicleSignalSource {
    val name: String
    fun start(listener: VehicleEventListener)
    fun stop()

    /** 현재 값을 다시 읽어 보낸다(예: P 진입 때 문 상태 재조회). 지원하지 않으면 아무것도 하지 않는다. */
    fun refresh() {}
}

/**
 * 여러 문을 '하나라도 열려 있으면 열림'으로 묶는다. 1열 운전석·조수석을 묶으면 조수석 하차·동승자 배송도 문 사이클이 된다.
 * 각 문의 최신 값을 기억하고, 묶은 값을 돌려준다.
 */
class DoorUnion {
    private val open = HashMap<String, Boolean>()

    fun update(door: String, isOpen: Boolean): Boolean {
        open[door] = isOpen
        return open.values.any { it }
    }
}

/** 여러 원천(예: 도어=Pleos Door, 기어=AAOS, 주행거리=Pleos Odometer)을 하나로 합친다. */
class CompositeSignalSource(private val sources: List<VehicleSignalSource>) : VehicleSignalSource {
    override val name: String = sources.joinToString("+") { it.name }

    override fun start(listener: VehicleEventListener) {
        sources.forEach { it.start(listener) }
    }

    override fun stop() {
        sources.asReversed().forEach { runCatching { it.stop() } }
    }

    override fun refresh() = sources.forEach { runCatching { it.refresh() } }
}

/**
 * 코드로 신호를 넣는 목 원천. 단위 테스트와 adb 브로드캐스트 시연 모드가 공용으로 쓴다.
 */
class ManualSignalSource(override val name: String = "manual") : VehicleSignalSource {
    private var listener: VehicleEventListener? = null

    val isStarted: Boolean get() = listener != null

    override fun start(listener: VehicleEventListener) {
        this.listener = listener
    }

    override fun stop() {
        listener = null
    }

    fun emit(event: VehicleEvent) {
        listener?.onVehicleEvent(event)
    }

    fun gear(gear: Gear, atMillis: Long) = emit(VehicleEvent.GearChanged(gear, atMillis))
    fun door(open: Boolean, atMillis: Long) = emit(VehicleEvent.DoorChanged(open, atMillis))
    fun odometer(km: Double, atMillis: Long) = emit(VehicleEvent.OdometerUpdated(km, atMillis))
}

/**
 * 같은 신호(기어)를 내는 원천이 여럿일 때 한 원천(owner)만 따른다.
 * 두 원천을 그대로 합치면 늦게 온 중복 값이 순서를 뒤집어(P→D 뒤에 늦은 P) 거짓 정차를 만들 수 있다.
 *
 * - 처음 기어 값을 보낸 원천이 owner가 된다.
 * - owner가 [handoverMillis] 이상 조용한 상태에서 다른 원천이 **새 값**을 보내면 그 원천이 owner를 넘겨받는다
 *   (초기값만 읽히고 구독 이벤트는 안 오는 원천에 묶이지 않도록).
 * - 기어가 아닌 이벤트(도어·주행거리·오류)는 모든 원천에서 통과시킨다.
 */
class FirstGearWinsSource(
    private val candidates: List<VehicleSignalSource>,
    private val handoverMillis: Long = 2_000L,
) : VehicleSignalSource {
    override val name: String = "first-gear(" + candidates.joinToString("|") { it.name } + ")"
    private var owner: VehicleSignalSource? = null
    private var ownerLastAt = Long.MIN_VALUE
    private var lastGear: Gear? = null

    val gearOwner: String? get() = owner?.name

    override fun start(listener: VehicleEventListener) {
        candidates.forEach { source ->
            source.start { event ->
                if (event !is VehicleEvent.GearChanged) {
                    listener.onVehicleEvent(event)
                    return@start
                }
                val current = owner
                val takeOver = current != null && current !== source &&
                    event.gear != lastGear && event.atMillis - ownerLastAt >= handoverMillis
                if (current == null || current === source || takeOver) {
                    owner = source
                    ownerLastAt = event.atMillis
                    lastGear = event.gear
                    listener.onVehicleEvent(event)
                }
            }
        }
    }

    override fun refresh() = candidates.forEach { runCatching { it.refresh() } }

    override fun stop() {
        candidates.forEach { runCatching { it.stop() } }
        owner = null
        lastGear = null
    }
}
