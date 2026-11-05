package app.nextstop.drive

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.io.File
import app.nextstop.BuildConfig
import app.nextstop.core.checkpoint.CheckpointConfig
import app.nextstop.core.checkpoint.CheckpointEffect
import app.nextstop.core.checkpoint.IgnoreReason
import app.nextstop.core.display.DriveDisplay
import app.nextstop.core.geo.LatLng
import app.nextstop.core.model.DestinationCandidate
import app.nextstop.core.model.PlaceCapture
import app.nextstop.core.model.Stop
import app.nextstop.core.model.StopEdit
import app.nextstop.core.model.StopImport
import app.nextstop.core.nav.NavEvent
import app.nextstop.core.nav.Navigator
import app.nextstop.core.nav.RecordingNavigator
import app.nextstop.core.route.RouteOptimizer
import app.nextstop.core.route.RoutePlan
import app.nextstop.core.run.DeliveryRun
import app.nextstop.core.run.RunCodec
import app.nextstop.core.run.RunMemento
import app.nextstop.core.run.RunListener
import app.nextstop.core.run.RunSnapshot
import app.nextstop.core.sample.SampleRoutes
import app.nextstop.core.settle.SettlementParams
import app.nextstop.core.time.Clock
import app.nextstop.core.vehicle.CompositeSignalSource
import app.nextstop.core.vehicle.FirstGearWinsSource
import app.nextstop.core.vehicle.Gear
import app.nextstop.core.vehicle.VehicleEvent
import app.nextstop.core.vehicle.VehicleEventListener
import app.nextstop.core.vehicle.VehicleSignalSource
import app.nextstop.platform.BroadcastSignalSource
import app.nextstop.platform.CarConnection
import app.nextstop.platform.CarGearSource
import app.nextstop.platform.PleosDoorSource
import app.nextstop.platform.PleosNavigator
import app.nextstop.platform.PleosOdometerSource
import app.nextstop.platform.PleosVehicleSession
import app.nextstop.platform.UxRestrictionWatcher

/** 신호 원천 구성. */
enum class SignalMode {
    /** Pleos Door + AAOS 기어 + Odometer (디버그 빌드는 adb 목 신호도 함께 받음) */
    VEHICLE,
    /** adb 브로드캐스트·화면 시연 패널만 */
    DEMO,
}

/**
 * 앱 수명 동안 하나만 존재하는 운행 상태 보관소. 액티비티가 다시 만들어져도 운행이 유지된다.
 * 모든 메서드는 메인 스레드에서 호출한다.
 */
object RunStore : VehicleEventListener {
    const val TAG = "NextStop"

    var mode: SignalMode = SignalMode.VEHICLE
        private set

    /** 운행 시각의 기준. 화면 테스트·영상 장면이 정산 시간을 재현할 수 있도록 바꿀 수 있다. */
    internal var clock: Clock = Clock.SYSTEM

    /** 운행 화면이 지금 보이는가(DriveActivity onResume/onPause). 안 보이면 완료를 알림으로 알린다. */
    @Volatile var foreground = false
    private var notifier: CompletionNotifier? = null
    private var appContext: Context? = null

    /** 운행 중 포그라운드 서비스를 요청해 둔 상태인가(RunService). */
    internal var serviceRequested = false
        private set
    private var lastRunSnapshot: RunSnapshot? = null

    var useNaviHelper = true
        private set

    /** 정산 가정값. ensureStarted에서 저장값을 읽고, 정산 화면에서 바꾸면 저장한다. */
    var settlementParams: SettlementParams = Prefs.DEFAULT
        private set

    fun updateSettlement(context: Context, v: SettlementParams) {
        settlementParams = v
        Prefs.setSettlement(context, v)
    }

    private val optimizer = RouteOptimizer()
    /** 빈 계획(배송지 없음). 그릴 것이 없으므로 출발점 좌표는 의미가 없다. */
    private val EMPTY_PLAN = RoutePlan(LatLng(0.0, 0.0), emptyList(), 0.0, exact = true)
    /** 배송지 목록(편집 순서). 첫 배송지가 동선의 출발점이다(사용자가 '출발'로 바꿀 수 있음). 시연 모드에서만 샘플로 채운다. */
    var inputStops: List<Stop> = emptyList()
        private set
    /**
     * true면 시연 샘플: 샘플 출발점([SampleRoutes.DEPOT])에서 짠다. false면 출발점 좌표 없이 첫 배송지 기준(위치 권한 0, 고정 좌표 0).
     */
    var sampleOrigin = false
        private set
    /** true면 최적화하지 않고 입력(편집) 순서 그대로 돈다. */
    var keepOrder = false
        private set
    var plan: RoutePlan = EMPTY_PLAN
        private set
    /** 동선 그림·거리 비교의 출발점. */
    val depot: LatLng get() = plan.start
    /** 배송지 직접 추가: 내비 장소 검색에서 고른 목적지를 한 번 받는다. */
    val placeCapture by lazy { PlaceCapture(clock) }
    /** 직접 추가 결과(화면 알림용). */
    var onPlaceAdded: ((StopEdit.AddResult) -> Unit)? = null
    private var planFile: File? = null
    val inputOrderKm: Double get() = optimizer.estimateKm(depot, inputStops)

    private fun planFor(stops: List<Stop>, keep: Boolean): RoutePlan =
        if (sampleOrigin) optimizer.plan(SampleRoutes.DEPOT, stops, keep)
        else optimizer.planFromFirst(stops, keep) ?: EMPTY_PLAN

    var run: DeliveryRun? = null
        private set
    var lastGear: Gear = Gear.UNKNOWN
        private set
    var doorOpen = false
        private set
    var lastOdometerKm: Double? = null
        private set
    /** null = UX 제한 서비스 정보 없음 */
    var uxRestricted: Boolean? = null
        private set
    var lastNavEvent: NavEvent? = null
        private set
    var lastSourceError: String? = null
        private set

    @Volatile private var started = false
    private var session: PleosVehicleSession? = null
    private var car: CarConnection? = null
    @Volatile private var source: VehicleSignalSource? = null
    var demoSource: BroadcastSignalSource? = null
        private set
    private var ux: UxRestrictionWatcher? = null
    private var navigator: Navigator = RecordingNavigator()
    private var pleosNavigator: PleosNavigator? = null
    private val observers = mutableListOf<RunListener>()
    private var stateFile: File? = null
    private const val STATE_FILE = "run-state.txt"
    private const val PLAN_FILE = "plan-stops.txt"
    private var planLoaded = false
    private var checkpoint = CheckpointConfig(minParkedMillis = 0)
    private val signalObservers = mutableListOf<() -> Unit>()

    /**
     * 신호 직접 읽기 주기. 콜백이 유실돼도(11/4 CI 리허설 ci-20261104-0206: P·D 콜백이 끝내 안 옴) 현재 기어·문 값을 다시 읽어
     * 바뀐 것만 보낸다(원천이 VHAL 시각·이전 값으로 중복·역전을 거른다).
     */
    private const val POLL_MS = 2_000L
    /**
     * 직접 읽기는 바인더 호출이라 메인 스레드에서 하지 않는다(11/5 리허설 ci-20261105-2039: 기기가 굳은 동안 앱이 ANR로 종료).
     * 읽은 값은 각 원천이 메인 스레드로 옮겨 보낸다.
     */
    private val pollHandler by lazy { android.os.Handler(android.os.HandlerThread("ns-poll").also { it.start() }.looper) }
    private val poll = object : Runnable {
        override fun run() {
            if (!started) return
            runCatching { source?.refresh() }
            pollHandler.postDelayed(this, POLL_MS)
        }
    }

    /** 주행 중으로 볼지. UX 제한 정보가 있어도 P가 아니면 제한으로 본다(보수적). */
    val restricted: Boolean get() = uxRestricted == true || (lastGear != Gear.PARK && lastGear != Gear.UNKNOWN)

    fun ensureStarted(context: Context, mode: SignalMode, useNavi: Boolean) {
        if (started && mode == this.mode && useNavi == useNaviHelper) return
        shutdown()
        if (started && mode != this.mode) {
            // 신호 원천이 바뀌면 이전 원천의 마지막 값(예: 시연 주행거리 1,000km ↔ 실차 0km)을 새 운행에 섞지 않는다.
            lastGear = Gear.UNKNOWN
            doorOpen = false
            lastOdometerKm = null
        }
        this.mode = mode
        this.useNaviHelper = useNavi
        val app = context.applicationContext
        settlementParams = Prefs.settlement(app)
        appContext = app
        if (notifier == null) notifier = runCatching { CompletionNotifier(app) }.getOrNull()
        checkpoint = CheckpointConfig(
            minParkedMillis = 0,
            doorBeforeParkMillis = Prefs.doorBeforeParkMs(app, CheckpointConfig.DEFAULT_DOOR_BEFORE_PARK_MILLIS),
            doorAfterDepartMillis = Prefs.doorAfterDepartMs(app, CheckpointConfig.DEFAULT_DOOR_AFTER_DEPART_MILLIS),
        )
        val demo = BroadcastSignalSource(app)
        demoSource = demo
        val sources = mutableListOf<VehicleSignalSource>()
        // UX 제한 상태는 시연 신호 모드에서도 플랫폼 값을 따른다(완료 연출의 움직임 여부 결정).
        val c = CarConnection(app).also { car = it }
        if (mode == SignalMode.VEHICLE) {
            val s = PleosVehicleSession(app).also { session = it }
            sources += PleosDoorSource(s, if (Prefs.frontDoors(app)) PleosDoorSource.FRONT_ROW else listOf(PleosDoorSource.DRIVER))
            // 기어는 AAOS GEAR_SELECTION(Pleos Gear API는 SDK가 deprecated로 표시하고 GEAR_SELECTION 사용을 안내).
            // 디버그 빌드에서는 시연 신호도 기어를 낼 수 있어, 먼저 응답한 원천을 따르게 중재한다.
            sources += if (BuildConfig.DEBUG) FirstGearWinsSource(listOf(CarGearSource(c), demo)) else CarGearSource(c)
            sources += PleosOdometerSource(s)
            if (!BuildConfig.DEBUG) demoSource = null
        } else {
            sources += demo
        }
        val base: Navigator = if (useNavi) PleosNavigator(app).also { it.initialize() } else RecordingNavigator()
        pleosNavigator = base as? PleosNavigator
        // 다음 목적지는 D(완료 확정) 순간 바로 보낸다. 예전의 1.5초 지연(완료 연출을 먼저 보이려던 것)은 연출과 함께 없앴다.
        navigator = base
        navigator.setListener { e ->
            lastNavEvent = e
            // 가져온 배송지 id에는 좌표가 들어갈 수 있어 이벤트 종류만 남긴다.
            Log.i(TAG, "NS|NAV|${e::class.simpleName}" + if (e is NavEvent.Failed) "|${e.reason}" else "")
            notifySignals()
        }
        car?.let { c ->
            ux = UxRestrictionWatcher(c).also { w ->
                w.start { restrictedNow ->
                    uxRestricted = restrictedNow
                    Log.i(TAG, "NS|UX|restricted=$restrictedNow")
                    notifySignals()
                }
            }
        }
        stateFile = File(app.filesDir, STATE_FILE)
        planFile = File(app.filesDir, PLAN_FILE)
        if (run == null && !planLoaded) loadPlan()
        // 샘플 경로는 시연 모드에서만: 빈 목록으로 시연을 시작하면 샘플을 채운다.
        if (mode == SignalMode.DEMO && run == null && inputStops.isEmpty()) useSampleStops()
        pleosNavigator?.let { nav ->
            // 검색 결과를 목적지로 정하면 목적지가 바뀐다 → 좌표·POI를 묻는다
            nav.onDestinationChanged = { if (placeCapture.armed && run == null) nav.requestDestinationInfo() }
            // 결과에서 '목적지로'를 누르면 목적지 변경 알림 없이 경로 안내만 시작된다 → 그때도 묻는다(받은 뒤 경로는 acceptPlace가 취소)
            nav.onRouteStarted = {
                if (placeCapture.armed && run == null) {
                    Log.i(TAG, "NS|PLACE|route-started → ask destination")
                    nav.requestDestinationInfo()
                }
            }
            nav.onDestination = { c -> if (run == null) acceptPlace(c) }
        }
        // 모드가 바뀌어 내비·신호가 새로 만들어졌으면 진행 중 운행을 새 내비에 다시 묶고,
        // 앱이 새로 시작됐으면 저장된 운행을 복원한다(시동을 껐다 켜도 이어서 진행).
        val memento = run?.memento() ?: loadState()
        if (memento != null) {
            val restored = DeliveryRun.restore(memento, navigator, clock, checkpoint)
            val bootNow = bootCount(app)
            val bootThen = Prefs.runBootCount(app)
            attach(restored)
            // 시동 끔: 정차(P) 중 헤드유닛이 재부팅됐으면 꺼진 동안 문 이벤트를 받을 수 없었다 → 문 사이클 1회로 본다.
            // 앱만 다시 시작된 경우(부팅 횟수 같음)는 인정하지 않는다(그때는 문 이벤트가 살아 있었을 수 있음).
            if (run === restored && bootThen >= 0 && bootNow >= 0 && bootNow != bootThen && restored.snapshot().isParked) {
                Log.i(TAG, "NS|POWER|boot $bootThen→$bootNow while parked")
                restored.powerCycled()
            }
            if (!restored.snapshot().isFinished) restored.resumeNavigation()
            Log.i(TAG, "NS|RESTORE|done=${restored.snapshot().done}/${restored.snapshot().total}")
        }
        source = CompositeSignalSource(sources).also { it.start(this) }
        started = true
        pollHandler.removeCallbacks(poll)
        pollHandler.postDelayed(poll, POLL_MS)
        Log.i(TAG, "NS|START|mode=$mode navi=$useNavi sources=${source?.name}")
    }

    /** 문 범위·보정 창 같은 신호 설정을 바꾼 뒤 신호 원천을 다시 만든다(진행 중 운행은 그대로 이어진다). */
    fun restartSignals(context: Context) {
        if (!started) return
        started = false
        ensureStarted(context, mode, useNaviHelper)
    }

    fun shutdown() {
        pollHandler.removeCallbacks(poll)
        source?.stop()
        source = null
        ux?.stop()
        ux = null
        navigator.cancel()
        pleosNavigator?.release()
        pleosNavigator = null
        navigator = RecordingNavigator()
        session?.release()
        session = null
        car?.release()
        car = null
        demoSource = null
        started = false
    }

    /**
     * 내비의 최근 목적지(recent)·즐겨찾기(bookmark)를 배송지로 가져온다. 결과는 비동기.
     * @return 요청을 보냈으면 true(내비 미사용·초기화 실패면 false)
     */
    fun importFromNavi(kind: String, onResult: (imported: Int) -> Unit): Boolean {
        val nav = pleosNavigator ?: return false
        nav.onDestinations = { source, candidates ->
            if (source == kind && run == null) {
                val stops = StopImport.fromDestinations(candidates)
                Log.i(TAG, "NS|IMPORT|$source candidates=${candidates.size} stops=${stops.size}")
                if (stops.isNotEmpty()) { sampleOrigin = false; setStops(stops) }
                onResult(stops.size)
                notifySignals()
            }
        }
        return if (kind == "bookmark") nav.requestBookmarks() else nav.requestRecentDestinations()
    }

    fun useSampleStops() {
        if (run != null) return
        sampleOrigin = true
        setStops(SampleRoutes.DEMO_5)
    }

    /** 이 배송지에서 출발한다(동선의 출발점). 위치 권한 없이 사용자가 고른다. */
    fun makeStart(id: String) {
        if (run != null) return
        val s = inputStops.firstOrNull { it.id == id } ?: return
        sampleOrigin = false
        if (keepOrder) keepOrder = false
        setStops(listOf(s) + inputStops.filter { it.id != id })
    }

    fun setStops(stops: List<Stop>) {
        check(run == null) { "운행 중에는 배송지를 바꿀 수 없다" }
        inputStops = stops
        plan = planFor(stops, keepOrder)
        savePlan()
    }

    fun setKeepOrder(keep: Boolean) {
        if (run != null) return
        keepOrder = keep
        // 고정으로 바꾸면 지금 보이는 최적 순서를 시작점으로 삼는다(사용자가 그 순서에서 옮긴다)
        if (keep) inputStops = plan.orderedStops
        setStops(inputStops)
    }

    fun removeStop(id: String) { if (run == null) setStops(StopEdit.remove(inputStops, id)) }

    fun moveStopUp(id: String) { if (run == null) setStops(StopEdit.moveUp(inputStops, id)) }

    fun clearStops() {
        if (run != null) return
        sampleOrigin = false
        setStops(emptyList())
    }

    /**
     * 배송지 직접 추가 1단계: 내비 장소 검색 화면을 연다. 사용자가 결과를 목적지로 정하면 자동으로 받는다.
     * @return 내비가 없으면 false(시연·목 내비 모드)
     */
    fun searchPlace(keyword: String): Boolean {
        val nav = pleosNavigator ?: return false
        if (run != null) return false
        armPlace(nav, keyword.trim())
        Log.i(TAG, "NS|PLACE|search")
        return nav.searchPlace(keyword.trim())
    }

    /**
     * 배송지 직접 추가(검색어 없이): 내비를 앞으로 띄우고, 사용자가 내비에서 직접 검색해 목적지로 정한 곳을 받는다.
     * 검색 화면 요청이 듣지 않는 내비에서도 되는 길(앱으로 돌아오면 [checkPendingPlace]가 목적지를 읽는다).
     */
    fun pickInNavi(): Boolean {
        val nav = pleosNavigator ?: return false
        if (run != null) return false
        armPlace(nav, "")
        Log.i(TAG, "NS|PLACE|pick")
        return nav.openNavi()
    }

    /** 대기 시작 + 지금 내비에 있던 목적지를 먼저 물어 기준으로 적어 둔다(옛 목적지를 고른 곳으로 착각하지 않게 — [PlaceCapture]). */
    private fun armPlace(nav: PleosNavigator, keyword: String) {
        placeCapture.arm(keyword, expectBaseline = true)
        nav.requestDestinationInfo()
    }

    /** 앱으로 돌아왔을 때: 검색을 연 뒤라면 그사이 정한 목적지를 한 번 묻는다(앱이 뒤에서 종료됐다 다시 떠도 받는다). */
    fun checkPendingPlace() {
        if (placeCapture.armed && run == null) pleosNavigator?.requestDestinationInfo()
    }

    private fun acceptPlace(c: DestinationCandidate) {
        val picked = placeCapture.accept(c) ?: return
        val r = StopEdit.add(inputStops, picked)
        if (r is StopEdit.AddResult.Added && sampleOrigin) sampleOrigin = false
        when (r) {
            is StopEdit.AddResult.Added -> setStops(r.stops)
            is StopEdit.AddResult.Merged -> setStops(r.stops)
            else -> Unit
        }
        // 고른 장소로 경로 안내가 시작되지 않게 취소한다(배송 순서는 앱이 정한다)
        navigator.cancel()
        Log.i(TAG, "NS|PLACE|${r::class.simpleName} stops=${inputStops.size}")
        if (!foreground) notifier?.showPlaceAdded((r as? StopEdit.AddResult.Added)?.stop?.label ?: (r as? StopEdit.AddResult.Merged)?.stop?.label, inputStops.size)
        onPlaceAdded?.invoke(r)
        notifySignals()
    }

    private fun savePlan() {
        val f = planFile ?: return
        runCatching { f.writeText(RunCodec.encodePlan(inputStops, keepOrder) + if (sampleOrigin) "sample\n" else "") }
    }

    private fun loadPlan() {
        planLoaded = true
        val f = planFile ?: return
        if (!f.exists()) return
        val text = f.readText()
        RunCodec.decodePlan(text)?.let { (stops, keep) ->
            keepOrder = keep
            inputStops = stops
            sampleOrigin = text.lines().any { it == "sample" }
            plan = planFor(stops, keep)
        }
    }

    fun startRun() {
        if (run != null) return
        val r = DeliveryRun(plan.orderedStops, navigator, clock, checkpoint)
        attach(r)
        // 시작 전 마지막 신호를 먼저 넣어 상태기계가 현재 기어·도어를 알게 한다.
        val t = clock.nowMillis()
        if (lastGear != Gear.UNKNOWN) r.onVehicleEvent(VehicleEvent.GearChanged(lastGear, t))
        if (doorOpen) r.onVehicleEvent(VehicleEvent.DoorChanged(true, t))
        lastOdometerKm?.let { r.onVehicleEvent(VehicleEvent.OdometerUpdated(it, t)) }
        r.start()
    }

    private fun attach(r: DeliveryRun) {
        r.addListener(RunListener { snap, effects ->
            effects.filterNot { it is CheckpointEffect.Ignored && it.reason == IgnoreReason.REPEATED_GEAR }
                .forEach { Log.i(TAG, "NS|EFFECT|${it.logName()}|${it.logDetail()}") }
            // 의미 있는 변화(무시된 입력 제외)마다 저장한다.
            if (effects.any { it !is CheckpointEffect.Ignored }) saveState(r)
            // P 진입 때 문 상태를 다시 읽는다(열림 이벤트를 놓쳤어도 지금 열려 있으면 사이클이 이어진다).
            if (effects.any { it is CheckpointEffect.Parked }) source?.refresh()
            if (!foreground) DriveDisplay.completionCue(lastRunSnapshot, snap, effects)?.let { notifier?.show(it) }
            appContext?.let { c ->
                if (snap.isFinished) { RunService.stop(c); serviceRequested = false }
                else if (effects.any { it !is CheckpointEffect.Ignored }) RunService.update(c, snap)
            }
            lastRunSnapshot = snap
            observers.toList().forEach { it.onRunChanged(snap, effects) }
        })
        run = r
        lastRunSnapshot = r.snapshot()
        saveState(r)
        // 내비가 앞에 있는 동안에도 신호를 받도록 운행 중에는 프로세스를 포그라운드로 유지한다(RunService)
        if (!r.snapshot().isFinished) appContext?.let { serviceRequested = RunService.start(it) }
    }

    private fun saveState(r: DeliveryRun) {
        val f = stateFile ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(RunCodec.encode(r.memento()))
            appContext?.let { Prefs.setRunBootCount(it, bootCount(it)) }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }.onFailure { Log.w(TAG, "NS|SAVE|fail ${it.message}") }
    }

    private fun loadState(): RunMemento? {
        val f = stateFile ?: return null
        if (!f.exists()) return null
        return runCatching { RunCodec.decode(f.readText()) }.getOrNull().also {
            if (it == null) f.delete() // 형식이 다르거나 깨진 저장본은 버리고 새로 시작
        }
    }

    /** 테스트용: 프로세스가 죽은 것처럼 메모리 상태만 버린다(저장본은 남김). */
    internal fun simulateProcessDeath() {
        shutdown()
        run = null
        // 새 프로세스의 기본값으로 되돌린다 — 배송지 목록은 파일에서 다시 읽혀야 한다
        planLoaded = false
        keepOrder = false
        sampleOrigin = false
        inputStops = emptyList()
        plan = EMPTY_PLAN
        placeCapture.disarm()
        lastGear = Gear.UNKNOWN
        doorOpen = false
        lastOdometerKm = null
    }

    /**
     * 운행 정리('새 운행'·초기화). 수령인 정보(배송지 이름·주소·좌표)는 운행이 끝나면 기기에 남기지 않는다:
     * 운행 상태 파일과 배송지 목록 파일을 지운다. 남는 것은 사용자가 내보낸 운행기록(날짜·계기판 거리·건수 — 숫자만, [TripLog])과
     * 정산 가정값뿐이다. 시연 모드면 가상 샘플을 다시 채운다.
     */
    fun resetRun() {
        stateFile?.delete()
        val hadStops = inputStops.isNotEmpty() || run != null
        run = null
        if (hadStops) {
            sampleOrigin = false
            keepOrder = false
            inputStops = emptyList()
            plan = EMPTY_PLAN
            planFile?.delete()
            placeCapture.disarm()
            if (mode == SignalMode.DEMO && started) useSampleStops()
        }
        appContext?.let { RunService.stop(it) }
        serviceRequested = false
        navigator.cancel()
        notifySignals()
    }

    /** 방금 센 1건 되돌리기(정차 중에만). 끝난 운행을 되돌리면 운행이 다시 열린다. */
    fun undoLast() {
        val r = run ?: return
        if (restricted) return
        val wasFinished = r.snapshot().isFinished
        r.undoLast()
        if (wasFinished && !r.snapshot().isFinished) appContext?.let { serviceRequested = RunService.start(it) }
    }

    fun snapshot(): RunSnapshot? = run?.snapshot()

    private fun bootCount(c: Context): Int = Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun addRunObserver(l: RunListener) { observers += l }
    fun removeRunObserver(l: RunListener) { observers -= l }
    fun addSignalObserver(l: () -> Unit) { signalObservers += l }
    fun removeSignalObserver(l: () -> Unit) { signalObservers -= l }

    override fun onVehicleEvent(event: VehicleEvent) {
        Log.i(TAG, "NS|SIGNAL|$event")
        when (event) {
            is VehicleEvent.GearChanged -> lastGear = event.gear
            is VehicleEvent.DoorChanged -> doorOpen = event.open
            is VehicleEvent.SourceError -> lastSourceError = "${event.source}: ${event.message}"
            is VehicleEvent.OdometerUpdated -> lastOdometerKm = event.km
        }
        run?.onVehicleEvent(event)
        notifySignals()
    }

    private fun notifySignals() = signalObservers.toList().forEach { it() }

    /** 로그에는 인덱스·시각·사유만(배송지 이름·좌표 제외). */
    private fun CheckpointEffect.logDetail(): String = when (this) {
        is CheckpointEffect.NavigateTo -> "index=$stopIndex"
        is CheckpointEffect.Parked -> "index=$stopIndex"
        is CheckpointEffect.StopCompleted -> "index=$stopIndex trigger=$trigger doorCycles=$doorCycles dwellMs=${parkedAt?.let { departedAt - it }}"
        is CheckpointEffect.StopDeferred -> "index=$stopIndex"
        is CheckpointEffect.StopSkipped -> "index=$stopIndex"
        is CheckpointEffect.AllDone -> "done"
        is CheckpointEffect.DoorCycleCredited -> "index=$stopIndex reason=$reason"
        is CheckpointEffect.StopUndone -> "index=$stopIndex"
        is CheckpointEffect.Ignored -> reason.name
    }

    private fun CheckpointEffect.logName(): String = when (this) {
        is CheckpointEffect.NavigateTo -> "NAVIGATE_TO"
        is CheckpointEffect.Parked -> "PARKED"
        is CheckpointEffect.StopCompleted -> "STOP_COMPLETED"
        is CheckpointEffect.StopDeferred -> "STOP_DEFERRED"
        is CheckpointEffect.StopSkipped -> "STOP_SKIPPED"
        is CheckpointEffect.AllDone -> "ALL_DONE"
        is CheckpointEffect.DoorCycleCredited -> "DOOR_CYCLE_CREDITED"
        is CheckpointEffect.StopUndone -> "STOP_UNDONE"
        is CheckpointEffect.Ignored -> "IGNORED"
    }
}
