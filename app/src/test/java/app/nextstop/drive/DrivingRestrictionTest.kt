package app.nextstop.drive

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import app.nextstop.core.geo.LatLng
import app.nextstop.core.model.Stop
import app.nextstop.core.vehicle.Gear
import app.nextstop.core.vehicle.VehicleEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration

/**
 * 심사 지적 대응 화면 테스트: 주행 제한 중 '큰 글자 2줄, 버튼 0개'(A1·A3·A5·C11), 시연 패널 접기(G3),
 * 정차 중 되돌리기(B4·B6·V2), 시동 끔 규칙(A7), 알림 등급·id(A4·C14), 수령인 정보 정리(T7).
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-xhdpi")
class DrivingRestrictionTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val demo = Intent().putExtra(DriveActivity.EXTRA_MODE, "demo").putExtra(DriveActivity.EXTRA_NAVI, false)

    @After
    fun tearDown() {
        RunStore.resetRun()
        RunStore.shutdown()
    }

    private fun idle(d: Duration = Duration.ofMillis(50)) = shadowOf(Looper.getMainLooper()).idleFor(d)
    private var t = System.currentTimeMillis()
    private fun send(e: (Long) -> VehicleEvent) {
        t += 1_000
        RunStore.demoSource!!.inject(e(t))
        idle()
    }
    private fun gear(g: Gear) = send { VehicleEvent.GearChanged(g, it) }
    private fun door(open: Boolean) = send { VehicleEvent.DoorChanged(open, it) }
    private fun deliver() { gear(Gear.PARK); door(true); door(false); gear(Gear.DRIVE) }

    private fun views(root: View): List<View> =
        listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    /** 화면에 실제로 보이는 버튼(시연 전용 손잡이는 버튼이 아니라 가장자리 표시). */
    private fun visibleButtons(root: View) = views(root).filter { it is Button && it.isShown }
    private fun visibleTexts(root: View) = views(root).filter { it is TextView && it !is Button && it.isShown && (it as TextView).text.isNotBlank() }
        .filter { it.tag != DriveActivity.TAG_DEMO_HANDLE }
        .map { (it as TextView).text.toString() }
    private fun tagged(root: View, tag: String) = root.findViewWithTag<View>(tag)

    @Test
    fun `last completion while driving shows two lines and no buttons, settlement opens only in P`() {
        val c = Robolectric.buildActivity(DriveActivity::class.java, demo).setup()
        val root = c.get().window.decorView
        RunStore.startRun()
        gear(Gear.DRIVE)
        repeat(5) { deliver() }
        assertTrue(RunStore.snapshot()!!.isFinished)
        assertTrue(RunStore.restricted)
        assertEquals("주행 중 버튼 0개", emptyList<View>(), visibleButtons(root))
        assertEquals("큰 글자 2줄", listOf("오늘 배송 끝", "5건 완료"), visibleTexts(root))

        gear(Gear.PARK)
        assertTrue("P에서 정산", visibleButtons(root).any { (it as Button).text == "새 운행" })
        c.pause().stop().destroy()
    }

    @Test
    fun `plan screen while restricted is one line and every running screen keeps two lines`() {
        val c = Robolectric.buildActivity(DriveActivity::class.java, demo).setup()
        val root = c.get().window.decorView
        idle()
        gear(Gear.DRIVE) // 운행을 시작하지 않고 주행(예: 집에서 캠프로)
        assertEquals(emptyList<View>(), visibleButtons(root))
        assertEquals(listOf("정차 후 배송지를 준비하세요"), visibleTexts(root))
        assertEquals(View.VISIBLE, tagged(root, DriveActivity.TAG_RESTRICTED_NOTICE).visibility)

        gear(Gear.PARK)
        RunStore.startRun()
        gear(Gear.DRIVE)
        assertEquals(emptyList<View>(), visibleButtons(root))
        assertEquals(2, visibleTexts(root).size)
        c.pause().stop().destroy()
    }

    @Test
    fun `demo panel folds into an edge handle while driving and keys still send signals`() {
        val c = Robolectric.buildActivity(DriveActivity::class.java, demo).setup()
        val root = c.get().window.decorView
        RunStore.startRun()
        gear(Gear.DRIVE)
        val panel = tagged(root, DriveActivity.TAG_DEMO_PANEL)
        val handle = tagged(root, DriveActivity.TAG_DEMO_HANDLE)
        assertEquals(View.GONE, panel.visibility)
        assertEquals(View.VISIBLE, handle.visibility)
        // 손잡이를 누르면 한 번 펼쳐지고, 신호를 보내면 다시 접힌다
        handle.performClick(); idle()
        assertEquals(View.VISIBLE, panel.visibility)
        // 키보드 P(노트북 시연): 화면을 만지지 않고 신호
        c.get().onKeyUp(KeyEvent.KEYCODE_P, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_P)); idle()
        assertEquals(Gear.PARK, RunStore.lastGear)
        assertEquals("정차 중에는 패널", View.VISIBLE, panel.visibility)
        c.get().onKeyUp(KeyEvent.KEYCODE_O, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_O)); idle()
        c.get().onKeyUp(KeyEvent.KEYCODE_C, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C)); idle()
        c.get().onKeyUp(KeyEvent.KEYCODE_D, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_D)); idle()
        assertEquals(1, RunStore.snapshot()!!.delivered)
        assertEquals(View.GONE, panel.visibility)
        c.pause().stop().destroy()
    }

    @Test
    fun `undo while parked puts the wrongly counted stop back in front`() {
        val c = Robolectric.buildActivity(DriveActivity::class.java, demo).setup()
        val root = c.get().window.decorView
        RunStore.startRun()
        val first = RunStore.snapshot()!!.currentStop!!
        gear(Gear.DRIVE)
        deliver() // 편의점 정차가 1건으로 세어짐
        assertEquals(1, RunStore.snapshot()!!.delivered)
        val undo = tagged(root, DriveActivity.TAG_UNDO)
        assertFalse("주행 중엔 버튼 없음", undo.isShown)
        gear(Gear.PARK)
        assertTrue(undo.isShown)
        undo.performClick(); idle()
        val snap = RunStore.snapshot()!!
        assertEquals(0, snap.delivered)
        assertEquals(first, snap.currentStop)
        assertFalse("되돌릴 것이 없으면 숨김", undo.isShown)
        c.pause().stop().destroy()
    }

    @Test
    fun `a head unit reboot while parked counts as the missed door cycle, an app restart does not`() {
        fun parkAtFirstStop(): org.robolectric.android.controller.ActivityController<DriveActivity> {
            val c = Robolectric.buildActivity(DriveActivity::class.java, demo).setup()
            RunStore.startRun()
            gear(Gear.DRIVE)
            gear(Gear.PARK)
            return c
        }
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 7)
        // 1) 앱만 다시 시작(부팅 횟수 같음): 문 사이클을 지어내지 않는다
        parkAtFirstStop().pause().stop().destroy()
        RunStore.simulateProcessDeath()
        var c = Robolectric.buildActivity(DriveActivity::class.java, demo).setup(); idle()
        gear(Gear.DRIVE)
        assertEquals(0, RunStore.snapshot()!!.delivered)
        c.pause().stop().destroy()
        RunStore.resetRun()

        // 2) 시동 끔 → 헤드유닛 재부팅(부팅 횟수 바뀜): 꺼진 동안 못 받은 문 사이클 1회로 본다
        parkAtFirstStop().pause().stop().destroy()
        RunStore.simulateProcessDeath()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 8)
        c = Robolectric.buildActivity(DriveActivity::class.java, demo).setup(); idle()
        gear(Gear.DRIVE)
        assertEquals(1, RunStore.snapshot()!!.delivered)
        c.pause().stop().destroy()
    }

    @Test
    fun `recreating the screen after process death does not re-run one-shot launch options`() {
        // 스크립트가 reset·sample을 붙여 띄운 화면이, 프로세스 종료 뒤 같은 인텐트로 다시 만들어져도 운행이 지워지지 않는다.
        val launch = Intent(demo).putExtra(DriveActivity.EXTRA_RESET, true)
        val first = Robolectric.buildActivity(DriveActivity::class.java, launch).setup()
        RunStore.startRun()
        gear(Gear.DRIVE)
        deliver()
        assertEquals(1, RunStore.snapshot()!!.delivered)
        first.pause().stop().destroy()
        RunStore.simulateProcessDeath()
        val again = Robolectric.buildActivity(DriveActivity::class.java, launch).setup(android.os.Bundle())
        idle()
        assertEquals("복원한 운행이 그대로", 1, RunStore.snapshot()?.delivered)
        again.pause().stop().destroy()
    }

    @Test
    fun `completion notice never pops up over the navi and ids do not collide`() {
        CompletionNotifier(app)
        val m = app.getSystemService(NotificationManager::class.java)
        val ch = m.getNotificationChannel(CompletionNotifier.CHANNEL)
        assertEquals("헤드업 없음", NotificationManager.IMPORTANCE_LOW, ch.importance)
        val ids = setOf(CompletionNotifier.NOTIFICATION_ID, CompletionNotifier.PLACE_NOTIFICATION_ID, RunService.NOTIFICATION_ID)
        assertEquals(3, ids.size)
    }

    @Test
    fun `starting a new run wipes recipient names and coordinates from the device`() {
        val c = Robolectric.buildActivity(DriveActivity::class.java, demo).setup()
        val secret = "수령인테스트 101동"
        RunStore.setStops(listOf(Stop("x1", secret, LatLng(37.51234, 127.04321)), Stop("x2", "$secret 2", LatLng(37.5200, 127.0500))))
        send { VehicleEvent.OdometerUpdated(12_000.0, it) }
        RunStore.startRun()
        gear(Gear.DRIVE)
        send { VehicleEvent.OdometerUpdated(12_003.5, it) }
        deliver(); deliver()
        gear(Gear.PARK)
        assertTrue(RunStore.snapshot()!!.isFinished)
        fun deviceText() = app.filesDir.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertTrue("운행 중에는 이어 하기 위해 저장한다", deviceText().contains("37.51234"))
        // 운행기록 내보내기: 숫자만
        val export = views(c.get().window.decorView).filterIsInstance<Button>().single { it.text == "운행기록 내보내기" }
        export.performClick(); idle()
        val newRun = views(c.get().window.decorView).filterIsInstance<Button>().single { it.text == "새 운행" }
        newRun.performClick(); idle()
        val left = deviceText() + (app.getExternalFilesDir(null)?.walkTopDown()?.filter { it.isFile }?.joinToString { it.readText() } ?: "")
        assertFalse("배송지 이름이 남지 않는다", left.contains("수령인테스트"))
        assertFalse("좌표가 남지 않는다", left.contains("37.51234") || left.contains("127.04321"))
        assertFalse(RunStore.inputStops.any { it.label.contains("수령인테스트") })
        assertNotEquals(0, RunStore.inputStops.size) // 시연 모드라 가상 샘플이 다시 채워짐
        c.pause().stop().destroy()
    }
}
