package app.nextstop.drive

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.nextstop.core.checkpoint.CheckpointEffect
import app.nextstop.core.checkpoint.CompletionTrigger
import app.nextstop.core.display.DriveDisplay
import app.nextstop.core.model.StopEdit
import app.nextstop.core.run.RunListener
import app.nextstop.core.run.RunSnapshot
import app.nextstop.core.settle.DistanceSource
import app.nextstop.core.settle.Settlement
import app.nextstop.core.settle.SettlementReport
import app.nextstop.core.settle.TripLog
import app.nextstop.core.vehicle.Gear
import app.nextstop.core.vehicle.VehicleEvent
import app.nextstop.platform.BroadcastSignalSource
import app.nextstop.probe.AdbArg
import app.nextstop.probe.ProbeActivity
import java.io.File
import java.time.ZoneId
import java.util.Locale

/**
 * 메인 화면. 계획(정차) → 운행(주행 중 2줄) → 정산(정차) 세 장면을 한 액티비티에서 바꿔 보여 준다.
 *
 * 실행 옵션(adb):
 *   --es mode demo      목 신호만(시연 패널 + adb 브로드캐스트)
 *   --ez navi false     NaviHelper 대신 기록용 목 내비
 *   --ez reset true     진행 중 운행 초기화
 *   --ez autostart true 바로 운행 시작(스크립트용, 기어 상관없음)
 *   --ez panel true     차량 신호 모드에서도 시연 패널 표시(디버그 빌드)
 */
class DriveActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var planView: View
    private lateinit var driveView: View
    private lateinit var settleView: ScrollView

    // 계획 화면
    private lateinit var planSketch: RouteSketchView
    private lateinit var planSummary: TextView
    private lateinit var planRows: LinearLayout
    private lateinit var placeInput: EditText
    private lateinit var orderToggle: android.widget.Button

    // 운행 화면
    private lateinit var card: LinearLayout
    private lateinit var headline: TextView
    private lateinit var subline: TextView
    private lateinit var detail: TextView
    private lateinit var hint: TextView
    private lateinit var progress: ProgressSegments
    private lateinit var parkedActions: LinearLayout
    private lateinit var statusLine: TextView
    private lateinit var overlay: CompletionOverlay
    private lateinit var demoPanel: LinearLayout

    // 정산 화면
    private lateinit var settleBody: LinearLayout

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSnapshot: RunSnapshot? = null
    private var animating = false

    private val runListener = RunListener { snap, effects -> onRun(snap, effects) }
    private val signalListener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startWith(intent)
        buildUi()
        setContentView(root)
        if (Prefs.voice(this)) startTts()
        // 내비가 앱을 덮은 동안 완료를 알리려면 알림 권한이 필요하다(정차 중 첫 실행 때 한 번 묻는다).
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** 음성 안내는 선택 기능(기본 꺼짐). 화면만으로 흐름이 완결되고, 음성 엔진이 없어도 아무 영향이 없다. */
    private fun startTts() {
        if (tts != null) return
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS &&
                tts?.setLanguage(Locale.KOREAN).let { it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED }
        }
    }

    private fun stopTts() {
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startWith(intent)
        lastSnapshot = RunStore.snapshot()
        render()
    }

    /**
     * 런처 아이콘처럼 모드를 지정하지 않은 실행은 지금 모드를 그대로 둔다
     * (시연 운행 중에 앱을 다시 열어도 차량 신호 모드로 바뀌지 않게 — 에뮬레이터 실측에서 발견).
     */
    private fun startWith(intent: Intent) {
        val mode = when (intent.getStringExtra(EXTRA_MODE)) {
            "demo" -> SignalMode.DEMO
            "vehicle" -> SignalMode.VEHICLE
            else -> RunStore.mode
        }
        val navi = if (intent.hasExtra(EXTRA_NAVI)) intent.getBooleanExtra(EXTRA_NAVI, true) else RunStore.useNaviHelper
        RunStore.ensureStarted(this, mode, navi)
        applyLaunchOptions(intent)
    }

    /**
     * 실측·시연 스크립트용: --ez reset true(운행 초기화), --ez autostart true(바로 운행 시작),
     * --ez clear_stops true(배송지 목록 비우기), --es place 키워드(한글은 u:퍼센트인코딩 가능, 배송지 추가 입력칸에 넣고 '내비에서 찾기'를 누른 것과 같음 — adb input은 한글을 못 친다),
     * --ez pick true('내비 열어 고르기'를 누른 것과 같음).
     */
    private fun applyLaunchOptions(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_RESET, false)) RunStore.resetRun()
        if (intent.getBooleanExtra(EXTRA_CLEAR_STOPS, false)) RunStore.clearStops()
        AdbArg.text(intent.getStringExtra(EXTRA_PLACE))?.takeIf { it.isNotBlank() }?.let { kw ->
            intent.removeExtra(EXTRA_PLACE)
            if (::placeInput.isInitialized) placeInput.setText(kw)
            val ok = RunStore.searchPlace(kw)
            Log.i(RunStore.TAG, "NS|PLACE|script search sent=$ok")
        }
        if (intent.getBooleanExtra(EXTRA_PICK, false)) {
            intent.removeExtra(EXTRA_PICK)
            Log.i(RunStore.TAG, "NS|PLACE|script pick sent=${RunStore.pickInNavi()}")
        }
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false) && RunStore.run == null) RunStore.startRun()
    }

    override fun onStart() {
        super.onStart()
        RunStore.addRunObserver(runListener)
        RunStore.addSignalObserver(signalListener)
        lastSnapshot = RunStore.snapshot()
        render()
    }

    override fun onResume() {
        super.onResume()
        RunStore.foreground = true
        // 내비 장소 검색에서 돌아왔으면 그사이 정한 목적지를 배송지로 받는다
        RunStore.onPlaceAdded = { r -> onPlaceAdded(r) }
        RunStore.checkPendingPlace()
    }

    override fun onPause() {
        RunStore.foreground = false
        super.onPause()
    }

    override fun onStop() {
        RunStore.removeRunObserver(runListener)
        RunStore.removeSignalObserver(signalListener)
        super.onStop()
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 상태 반영

    private fun onRun(snap: RunSnapshot, effects: List<CheckpointEffect>) {
        val cue = DriveDisplay.completionCue(lastSnapshot, snap, effects)
        lastSnapshot = snap
        if (cue != null) {
            speak(if (cue.isLast) "${cue.completedCount}건 완료. 오늘 배송 끝." else "${cue.completedCount}건 완료. 다음, ${cue.nextLabel}.")
            animating = true
            // 움직이는 연출은 플랫폼이 '주행 제한 아님'을 알려 줄 때만. 제한 중이거나 알 수 없으면 정적 확인 화면.
            val motion = RunStore.uxRestricted == false
            overlay.play(cue, if (snap.isFinished) settleView else card, motion) {
                animating = false
                render(animateProgress = motion)
            }
            return
        }
        if (effects.any { it is CheckpointEffect.StopDeferred }) speak("나중에 다시 들릅니다. 다음, ${snap.currentStop?.label}.")
        render()
    }

    private fun render(animateProgress: Boolean = false) {
        if (animating) {
            // 연출 중에는 카드 내용을 바꾸지 않는다(onReveal에서 한 번에 바꿈).
            // 다만 그사이 주행 제한이 켜지면 움직임을 즉시 멈추고 정적 확인으로 바꾼다.
            if (RunStore.uxRestricted != false && overlay.isMoving) overlay.stopMotion()
            renderStatus()
            return
        }
        val snap = RunStore.snapshot()
        planView.visibility = if (snap == null) View.VISIBLE else View.GONE
        driveView.visibility = if (snap != null && !snap.isFinished) View.VISIBLE else View.GONE
        settleView.visibility = if (snap?.isFinished == true) View.VISIBLE else View.GONE
        when {
            snap == null -> renderPlan()
            snap.isFinished -> renderSettlement(snap)
            else -> renderDrive(snap, animateProgress)
        }
        renderStatus()
    }

    private fun renderPlan() {
        val plan = RunStore.plan
        planSketch.set(RunStore.depot, RunStore.inputStops, plan.orderedStops)
        val saved = RunStore.inputOrderKm - plan.estimatedKm
        planSummary.text = "최적 동선 ${km(plan.estimatedKm)} · 입력 순서보다 ${km(saved.coerceAtLeast(0.0))} 짧음" +
            if (plan.exact) " (최적해)" else " (근사해)"
        if (plan.keptOrder) planSummary.text = "입력 순서 고정 · ${km(plan.estimatedKm)}"
        if (plan.orderedStops.isEmpty()) planSummary.text = "배송지를 추가하세요"
        orderToggle.text = if (RunStore.keepOrder) "순서: 입력대로 고정" else "순서: 자동 최적화"
        planRows.removeAllViews()
        plan.orderedStops.forEachIndexed { i, s ->
            val name = label(30f).apply {
                text = "${i + 1}. ${s.label}" + if (s.parcels > 1) "  (${s.parcels}개)" else ""
            }
            val r = row(name, spacingDp = 12f)
            if (RunStore.keepOrder && i > 0) r.addView(smallPill("▲") { RunStore.moveStopUp(s.id); render() })
            r.addView(smallPill("삭제") { RunStore.removeStop(s.id); render() })
            planRows.addView(r, lp(top = 8f))
        }
    }

    private fun smallPill(text: String, onClick: () -> Unit) = pill(text, onClick = onClick).apply {
        minWidth = dp(96f); minHeight = dp(64f); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12f) }
    }

    /** 배송지 직접 추가: 내비 장소 검색 화면을 연다. 결과를 목적지로 정하면 앱이 받아 목록 끝에 붙인다. */
    private fun searchPlace() {
        if (RunStore.restricted) return toast("정차(P) 상태에서 추가하세요")
        val kw = placeInput.text?.toString()?.trim().orEmpty()
        if (kw.isEmpty()) return toast("주소나 건물 이름을 입력하세요")
        if (!RunStore.searchPlace(kw)) return toast("내비 연결이 없습니다(시연 신호·목 내비 모드)")
        toast("내비에서 장소를 골라 목적지로 정하면 배송지에 추가됩니다")
    }

    private fun onPlaceAdded(r: StopEdit.AddResult) {
        when (r) {
            is StopEdit.AddResult.Added -> { toast("+ ${r.stop.label} · 오늘 ${RunStore.inputStops.size}곳"); placeInput.setText("") }
            is StopEdit.AddResult.Merged -> toast("${r.stop.label}에 1개 더(같은 건물) · ${r.stop.parcels}개")
            StopEdit.AddResult.Full -> toast("배송지는 최대 30곳입니다")
            StopEdit.AddResult.Invalid -> toast("목적지 좌표를 받지 못했습니다")
        }
        render()
    }

    private fun renderDrive(snap: RunSnapshot, animateProgress: Boolean) {
        val restricted = RunStore.restricted
        val c = DriveDisplay.card(snap, restricted)
        headline.text = c.headline
        subline.text = c.subline
        detail.text = c.detail.orEmpty()
        detail.visibility = if (c.detail == null) View.GONE else View.VISIBLE
        hint.text = c.hint.orEmpty()
        hint.visibility = if (c.hint == null) View.GONE else View.VISIBLE
        hint.setTextColor(if (snap.readyToComplete) Palette.ACCENT else Palette.WARN)
        progress.set(snap.done, snap.total, animateProgress)
        // 주행 중(UX 제한)에는 조작 버튼을 숨긴다.
        parkedActions.visibility = if (restricted) View.GONE else View.VISIBLE
    }

    private fun renderStatus() {
        val gear = when (RunStore.lastGear) {
            Gear.PARK -> "P"
            Gear.REVERSE -> "R"
            Gear.NEUTRAL -> "N"
            Gear.DRIVE -> "D"
            Gear.UNKNOWN -> "-"
        }
        val modeText = if (RunStore.mode == SignalMode.DEMO) "시연 신호" else "차량 신호"
        val door = if (RunStore.doorOpen) "문 열림" else "문 닫힘"
        val odo = RunStore.lastOdometerKm?.let { " · ${km(it)}" }.orEmpty()
        statusLine.text = "$modeText · 기어 $gear · $door$odo"
        val running = RunStore.snapshot()?.let { !it.isFinished } == true
        val panelAllowed = RunStore.demoSource != null && (RunStore.mode == SignalMode.DEMO || intent.getBooleanExtra(EXTRA_PANEL, false))
        demoPanel.visibility = if (panelAllowed && running) View.VISIBLE else View.GONE
    }

    private fun renderSettlement(snap: RunSnapshot) {
        val report = Settlement.settle(snap, RunStore.settlementParams, RunStore.plan.estimatedKm, RunStore.clock.nowMillis())
        Log.i(RunStore.TAG, "NS|SETTLE|$report")
        settleBody.removeAllViews()
        val auto = snap.records.count { it.trigger == CompletionTrigger.GEAR_DRIVE }
        settleBody.addView(label(36f, Palette.SUB).apply { text = "오늘 배송 · 문 닫고 D만으로 기록" })
        settleBody.addView(label(104f, Palette.ACCENT, bold = true).apply {
            text = "자동 완료 ${auto}/${snap.delivered}건"
        }, lp(top = 8f))
        settleBody.addView(label(48f, Palette.TEXT, bold = true).apply {
            text = report.netPerHourWon?.let { "시간당 실수익 ${won(it)}" } ?: "순수익 ${won(report.netWon)}"
        }, lp(top = 16f))
        lines(report).forEach { settleBody.addView(label(34f).apply { text = it }, lp(top = 12f)) }
        settleBody.addView(row(
            pill("새 운행", filled = true) { RunStore.resetRun(); lastSnapshot = null; render() },
            // 보조 기능: 업무용 차량 운행기록 형식으로 내보내기
            pill("운행기록 내보내기") { saveTripLog(snap) },
        ), lp(top = 32f))
        // 가정값은 정차 중 이 자리에서 바꿔 볼 수 있다(내 단가·연비로 다시 계산).
        val p = RunStore.settlementParams
        fun update(v: app.nextstop.core.settle.SettlementParams) {
            RunStore.updateSettlement(this, v)
            renderSettlement(snap)
        }
        settleBody.addView(label(22f, Palette.SUB).apply { text = "가정값 (눌러서 조정)" }, lp(top = 32f))
        listOf(
            stepper("건당 ${won(p.feePerParcelWon)}",
                { update(p.copy(feePerParcelWon = (p.feePerParcelWon - 50).coerceAtLeast(0))) },
                { update(p.copy(feePerParcelWon = p.feePerParcelWon + 50)) }),
            stepper("연비 ${"%.1f".format(p.efficiencyKmPerUnit)}km/L",
                { update(p.copy(efficiencyKmPerUnit = (p.efficiencyKmPerUnit - 0.5).coerceAtLeast(1.0))) },
                { update(p.copy(efficiencyKmPerUnit = p.efficiencyKmPerUnit + 0.5)) }),
            stepper("연료 ${won(p.energyPricePerUnitWon)}/L",
                { update(p.copy(energyPricePerUnitWon = (p.energyPricePerUnitWon - 50).coerceAtLeast(0))) },
                { update(p.copy(energyPricePerUnitWon = p.energyPricePerUnitWon + 50)) }),
        ).forEach { settleBody.addView(it, lp(top = 12f)) }
        settleBody.addView(label(20f, Palette.SUB).apply {
            maxLines = 2
            text = "운행기록 내보내기는 업무용승용차 운행기록부 열 구성을 따르며 세무 판단은 하지 않습니다."
        }, lp(top = 24f))
    }

    private fun lines(r: SettlementReport): List<String> = buildList {
        add("배송 ${r.deliveredStops}곳 · ${r.deliveredParcels}개" + if (r.skippedStops > 0) " · 건너뜀 ${r.skippedStops}" else "")
        add("매출 ${won(r.grossWon)} − 연료비 ${won(r.energyCostWon)} = 순수익 ${won(r.netWon)}")
        add("주행 ${km(r.distanceKm)} (" + (if (r.distanceSource == DistanceSource.ODOMETER) "계기판 실측" else "동선 추정") + ")")
        add("운행 ${r.elapsedMillis / 60_000}분" + (r.avgDwellMillis?.let { " · 평균 정차 ${it / 1000}초" } ?: ""))
        if (r.netPerHourWon == null) add("시간당 실수익은 ${Settlement.MIN_MINUTES_FOR_HOURLY}분 이상 운행하면 표시합니다")
        r.costPerParcelWon?.let { add("개당 비용 ${won(it)}" + (r.kmPerStop?.let { k -> " · 배송지당 ${km(k)}" } ?: "")) }
    }

    private fun saveTripLog(snap: RunSnapshot) {
        val row = TripLog.rowFor(snap, ZoneId.systemDefault())
        if (row == null) {
            Toast.makeText(this, "계기판 주행거리가 없어 운행기록을 만들 수 없습니다", Toast.LENGTH_LONG).show()
            return
        }
        val file = File(getExternalFilesDir(null), "trip-log.csv")
        val csv = TripLog.toCsv(listOf(row))
        if (file.exists()) file.appendText(csv.lines().drop(1).filter { it.isNotBlank() }.joinToString("\n", postfix = "\n"))
        else file.writeText(csv)
        Log.i(RunStore.TAG, "NS|TRIPLOG|${file.absolutePath}")
        Toast.makeText(this, "저장: ${file.name}", Toast.LENGTH_LONG).show()
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue")
    }

    // ---------------------------------------------------------------- 화면 구성

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        planView = buildPlan()
        driveView = buildDrive()
        settleView = ScrollView(this).apply {
            settleBody = column().apply { setPadding(dp(64f), dp(48f), dp(64f), dp(48f)) }
            addView(settleBody)
        }
        overlay = CompletionOverlay(this).apply { tag = TAG_OVERLAY }
        demoPanel = buildDemoPanel()
        statusLine = label(22f, Palette.SUB)

        root.addView(planView, FrameLayout.LayoutParams(-1, -1))
        root.addView(driveView, FrameLayout.LayoutParams(-1, -1))
        root.addView(settleView, FrameLayout.LayoutParams(-1, -1))
        root.addView(statusLine, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(20f); marginEnd = dp(32f)
        })
        root.addView(demoPanel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END).apply {
            marginEnd = dp(32f)
        })
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun buildPlan(): View {
        val left = column().apply {
            setPadding(dp(64f), dp(48f), dp(32f), dp(48f))
            addView(label(30f, Palette.SUB).apply { text = "다음집 · 오늘의 배송" })
            planSummary = label(30f, Palette.ACCENT).apply { maxLines = 2 }
            addView(planSummary, lp(top = 16f))
            addView(row(
                pill("운행 시작", filled = true) {
                    when {
                        RunStore.restricted -> toast("정차(P) 상태에서 시작하세요")
                        RunStore.plan.orderedStops.isEmpty() -> toast("배송지를 먼저 추가하세요")
                        else -> RunStore.startRun()
                    }
                    render()
                },
                pill("실측 화면") { startActivity(Intent(this@DriveActivity, ProbeActivity::class.java)) },
                spacingDp = 12f,
            ), lp(top = 28f))
            planRows = column()
            addView(planRows, lp(top = 20f))
            orderToggle = pill("순서: 자동 최적화") { RunStore.setKeepOrder(!RunStore.keepOrder); render() }
            addView(row(orderToggle, pill("모두 지우기") { RunStore.clearStops(); render() }, spacingDp = 12f), lp(top = 16f))
            addView(label(22f, Palette.SUB).apply { text = "배송지 추가 — 주소·건물 이름으로 내비에서 찾기" }, lp(top = 32f))
            placeInput = EditText(this@DriveActivity).apply {
                hint = "예: 다솜동 3구역, 서울역"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26f)
                setTextColor(Palette.TEXT)
                setHintTextColor(Palette.DIM)
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                minWidth = dp(420f)
                setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { searchPlace(); true } else false }
            }
            addView(row(placeInput, pill("내비에서 찾기", filled = true) { searchPlace() }, spacingDp = 12f), lp(top = 12f))
            addView(row(
                pill("내비 열어 고르기") {
                    if (RunStore.restricted) toast("정차(P) 상태에서 추가하세요")
                    else if (!RunStore.pickInNavi()) toast("내비 연결이 없습니다(시연 신호·목 내비 모드)")
                    else toast("내비에서 목적지를 정하고 다음집으로 돌아오면 배송지에 추가됩니다")
                },
                label(22f, Palette.SUB).apply { text = "내비에서 정한 목적지를 돌아올 때 받아 옵니다" },
                spacingDp = 12f,
            ), lp(top = 12f))
            addView(label(22f, Palette.SUB).apply { text = "내비에서 배송지 가져오기" }, lp(top = 32f))
            addView(row(
                pill("최근 목적지") { importStops("recent") },
                pill("즐겨찾기") { importStops("bookmark") },
                pill("샘플") { RunStore.useSampleStops(); render() },
                spacingDp = 12f,
            ), lp(top = 12f))
            addView(row(label(22f, Palette.SUB).apply { text = "음성 안내(선택)" }, voiceToggle(), spacingDp = 16f), lp(top = 20f))
            addView(label(22f, Palette.SUB).apply {
                maxLines = 3
                text = "P에서 문을 열고 닫은 뒤 D를 넣으면 1건 완료로 기록하고 다음 배송지를 내비에 보냅니다."
            }, lp(top = 24f))
        }
        planSketch = RouteSketchView(this).apply { background = roundedBg(Palette.CARD, dp(24f).toFloat()) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ScrollView(this@DriveActivity).apply { addView(left) }, LinearLayout.LayoutParams(0, -1, 1f))
            addView(planSketch, LinearLayout.LayoutParams(0, -1, 1f).apply {
                setMargins(dp(16f), dp(96f), dp(48f), dp(48f))
            })
        }
    }

    private fun buildDrive(): View {
        headline = label(104f, bold = true)
        subline = label(52f, Palette.SUB)
        detail = label(30f, Palette.SUB)
        hint = label(34f, Palette.WARN, bold = true)
        progress = ProgressSegments(this)
        card = column().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(28f, Palette.SUB).apply { text = "다음 배송지" })
            addView(headline, lp(top = 8f))
            addView(subline, lp(top = 20f))
            addView(detail, lp(top = 20f))
            addView(hint, lp(top = 20f))
        }
        parkedActions = row(
            pill("완료") { RunStore.run?.completeManually() },
            pill("나중에") { RunStore.run?.deferCurrent() },
            pill("건너뛰기") { RunStore.run?.skipCurrent() },
        ).apply { tag = TAG_PARKED_ACTIONS }
        return column().apply {
            setPadding(dp(64f), dp(64f), dp(64f), dp(40f))
            addView(card, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(progress, LinearLayout.LayoutParams(-1, dp(20f)).apply { topMargin = dp(24f) })
            addView(parkedActions, lp(top = 28f))
        }
    }

    /** Virtual Console이 안 될 때 시연용으로 차량 조작을 대신하는 버튼. 목 신호 경로와 동일. */
    private fun buildDemoPanel(): LinearLayout {
        fun send(e: VehicleEvent) = RunStore.demoSource?.inject(e)
        fun t() = RunStore.clock.nowMillis()
        return column().apply {
            tag = TAG_DEMO_PANEL
            setPadding(dp(20f), dp(16f), dp(20f), dp(20f))
            background = roundedBg(Palette.CARD, dp(24f).toFloat())
            addView(label(18f, Palette.SUB).apply { text = "시연 신호 (차량 조작 대신)" })
            listOf(
                pill("P") { send(VehicleEvent.GearChanged(Gear.PARK, t())) },
                pill("문 열기") { send(VehicleEvent.DoorChanged(true, t())) },
                pill("문 닫기") { send(VehicleEvent.DoorChanged(false, t())) },
                pill("D", filled = true) { send(VehicleEvent.GearChanged(Gear.DRIVE, t())) },
            ).forEach { b ->
                addView(b, LinearLayout.LayoutParams(dp(240f), -2).apply { topMargin = dp(12f) })
            }
            addView(label(14f, Palette.SUB).apply { text = "adb: ${BroadcastSignalSource.ACTION}" }, lp(top = 12f))
        }
    }

    private fun voiceToggle(): android.widget.Button {
        fun title() = if (Prefs.voice(this)) "음성 켬" else "음성 끔"
        return pill(title()) {}.also { b ->
            b.setOnClickListener {
                val on = !Prefs.voice(this)
                Prefs.setVoice(this, on)
                if (on) startTts() else stopTts()
                b.text = title()
            }
        }
    }

    /** 정차 중 내비에 저장해 둔 목적지를 배송지로 가져온다(외부 API 없이 Pleos 내비의 POI 그대로). */
    private fun importStops(kind: String) {
        if (RunStore.restricted) return toast("정차(P) 상태에서 가져오세요")
        val sent = RunStore.importFromNavi(kind) { n ->
            toast(if (n > 0) "배송지 ${n}곳을 가져와 동선을 다시 짰습니다" else "가져올 목적지가 없습니다")
            render()
        }
        if (!sent) toast("내비 연결이 없습니다(시연 신호·목 내비 모드)")
    }

    private fun lp(top: Float) = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(top) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_NAVI = "navi"
        const val EXTRA_PANEL = "panel"
        const val EXTRA_CLEAR_STOPS = "clear_stops"
        const val EXTRA_PLACE = "place"
        const val EXTRA_PICK = "pick"
        const val EXTRA_RESET = "reset"
        const val EXTRA_AUTOSTART = "autostart"
        const val TAG_PARKED_ACTIONS = "parkedActions"
        const val TAG_OVERLAY = "completionOverlay"
        const val TAG_DEMO_PANEL = "demoPanel"
    }
}
