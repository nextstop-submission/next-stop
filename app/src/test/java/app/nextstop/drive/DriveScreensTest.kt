package app.nextstop.drive

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import app.nextstop.core.settle.RunSummary
import app.nextstop.core.time.Clock
import app.nextstop.core.time.ManualClock
import app.nextstop.core.vehicle.Gear
import app.nextstop.core.vehicle.VehicleEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration

/**
 * 시연 모드로 앱 화면을 JVM에서 실제로 그려 PNG로 남긴다(에뮬레이터 없이 화면·연출 확인).
 * 2560×1440 차량 디스플레이 ≈ 1280×720dp(xhdpi).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-xhdpi")
class DriveScreensTest {

    private val outDir = File(System.getProperty("nextstop.screens") ?: "build/screens").also { it.mkdirs() }

    @After
    fun tearDown() {
        RunStore.resetRun()
        RunStore.shutdown()
        RunStore.clock = Clock.SYSTEM
    }

    private fun shot(activity: DriveActivity, name: String) {
        val root = activity.window.decorView
        idle(Duration.ofMillis(16))
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun idle(d: Duration) = shadowOf(Looper.getMainLooper()).idleFor(d)

    private fun send(e: VehicleEvent) {
        RunStore.demoSource!!.inject(e)
        idle(Duration.ofMillis(50))
    }

    private fun now() = System.currentTimeMillis()

    @Test
    fun `demo flow renders plan, drive, completion without a screen change, and settlement after P`() {
        val intent = Intent().putExtra(DriveActivity.EXTRA_MODE, "demo").putExtra(DriveActivity.EXTRA_NAVI, false)
        val controller = Robolectric.buildActivity(DriveActivity::class.java, intent).setup()
        val activity = controller.get()
        idle(Duration.ofMillis(100))
        // 계획은 정차(P) 중에 짠다 — 앞 테스트의 마지막 기어가 남아 '주행 중 안내' 화면이 찍히지 않게 P를 먼저 보낸다
        send(VehicleEvent.GearChanged(Gear.PARK, now()))
        shot(activity, "01-plan")

        RunStore.startRun()
        send(VehicleEvent.GearChanged(Gear.DRIVE, now()))
        idle(Duration.ofSeconds(2))
        shot(activity, "02-driving")

        send(VehicleEvent.GearChanged(Gear.PARK, now()))
        send(VehicleEvent.DoorChanged(true, now()))
        send(VehicleEvent.DoorChanged(false, now()))
        shot(activity, "03-parked-ready")

        send(VehicleEvent.GearChanged(Gear.DRIVE, now()))
        // 출발 순간: 전면 연출·지연 없이 같은 2줄 카드의 글자와 진행 막대만 바뀐다
        assertEquals(1, RunStore.snapshot()!!.delivered)
        shot(activity, "06-next-stop")

        // 문을 열지 않은 정차는 완료가 아니다
        send(VehicleEvent.GearChanged(Gear.PARK, now()))
        send(VehicleEvent.GearChanged(Gear.DRIVE, now()))
        idle(Duration.ofSeconds(2))
        assertEquals(1, RunStore.snapshot()!!.delivered)

        repeat(4) {
            send(VehicleEvent.GearChanged(Gear.PARK, now()))
            send(VehicleEvent.DoorChanged(true, now()))
            send(VehicleEvent.DoorChanged(false, now()))
            send(VehicleEvent.GearChanged(Gear.DRIVE, now()))
            idle(Duration.ofSeconds(3))
        }
        val snap = RunStore.snapshot()!!
        assertTrue(snap.isFinished)
        assertEquals(5, snap.delivered)
        shot(activity, "07-finished-driving")
        // 정산은 P(주행 제한 해제)에서만
        send(VehicleEvent.GearChanged(Gear.PARK, now()))
        shot(activity, "07-settlement")
        controller.pause().stop().destroy()
    }

    @Test
    fun `progress survives process death and the next checkpoint still completes`() {
        val intent = Intent().putExtra(DriveActivity.EXTRA_MODE, "demo").putExtra(DriveActivity.EXTRA_NAVI, false)
        val first = Robolectric.buildActivity(DriveActivity::class.java, intent).setup()
        RunStore.startRun()
        send(VehicleEvent.GearChanged(Gear.DRIVE, now()))
        send(VehicleEvent.GearChanged(Gear.PARK, now()))
        send(VehicleEvent.DoorChanged(true, now()))
        send(VehicleEvent.DoorChanged(false, now()))
        send(VehicleEvent.GearChanged(Gear.DRIVE, now()))
        idle(Duration.ofSeconds(3))
        assertEquals(1, RunStore.snapshot()!!.delivered)
        first.pause().stop().destroy()

        // 시동을 끈 것처럼 프로세스 상태를 버리고 앱을 다시 연다.
        RunStore.simulateProcessDeath()
        val second = Robolectric.buildActivity(DriveActivity::class.java, intent).setup()
        idle(Duration.ofMillis(200))
        val restored = RunStore.snapshot()!!
        assertEquals("완료 건수 유지", 1, restored.delivered)
        assertEquals("다음 배송지 유지", "다솜동 3구역", restored.currentStop!!.label)

        send(VehicleEvent.GearChanged(Gear.PARK, now()))
        send(VehicleEvent.DoorChanged(true, now()))
        send(VehicleEvent.DoorChanged(false, now()))
        send(VehicleEvent.GearChanged(Gear.DRIVE, now()))
        idle(Duration.ofSeconds(3))
        assertEquals(2, RunStore.snapshot()!!.delivered)
        second.pause().stop().destroy()
    }

    @Test
    fun `driving screen hides parked actions`() {
        val intent = Intent().putExtra(DriveActivity.EXTRA_MODE, "demo").putExtra(DriveActivity.EXTRA_NAVI, false)
        val controller = Robolectric.buildActivity(DriveActivity::class.java, intent).setup()
        val activity = controller.get()
        RunStore.startRun()
        send(VehicleEvent.GearChanged(Gear.DRIVE, now()))
        idle(Duration.ofSeconds(1))
        val actions = activity.window.decorView.findViewWithTag<View>(DriveActivity.TAG_PARKED_ACTIONS)
        assertEquals(View.GONE, actions.visibility)
        send(VehicleEvent.GearChanged(Gear.PARK, now()))
        assertEquals(View.VISIBLE, actions.visibility)
        controller.pause().stop().destroy()
    }

    @Test
    fun `settlement compares this run with past runs by hourly net, numbers only`() {
        // 2026-11-02(월) 19:00 KST에 시작한 저녁 운행. 지난 운행 두 번(오전 9시·저녁 7시 출발)을 기록해 둔다.
        val clock = ManualClock(1_793_613_600_000L)
        RunStore.clock = clock
        // '몇 시 출발'은 기기 시간대 기준 — CI(UTC)에서도 같은 화면이 나오게 한국 시간대로 고정
        val zone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"))
        try {
            val intent = Intent().putExtra(DriveActivity.EXTRA_MODE, "demo").putExtra(DriveActivity.EXTRA_NAVI, false)
            val controller = Robolectric.buildActivity(DriveActivity::class.java, intent).setup()
            val activity = controller.get()
            RunStore.forgetHistory()
            val hour = 3_600_000L
            RunStore.recordFinished(RunSummary(clock.nowMillis() - 34 * hour, 2 * hour, 12, 9.5, 9_000)) // 전날 09시, 시간당 4,500
            RunStore.recordFinished(RunSummary(clock.nowMillis() - 24 * hour, 2 * hour, 9, 11.0, 4_000)) // 전날 19시, 시간당 2,000
            send(VehicleEvent.GearChanged(Gear.PARK, clock.nowMillis()))
            RunStore.startRun()
            send(VehicleEvent.GearChanged(Gear.DRIVE, clock.nowMillis()))
            repeat(5) {
                clock.advanceMinutes(22)
                send(VehicleEvent.GearChanged(Gear.PARK, clock.nowMillis()))
                send(VehicleEvent.DoorChanged(true, clock.nowMillis()))
                clock.advanceSeconds(90)
                send(VehicleEvent.DoorChanged(false, clock.nowMillis()))
                send(VehicleEvent.GearChanged(Gear.DRIVE, clock.nowMillis()))
                idle(Duration.ofSeconds(1))
            }
            clock.advanceMinutes(5)
            send(VehicleEvent.GearChanged(Gear.PARK, clock.nowMillis()))
            val texts = mutableListOf<String>()
            fun collect(v: View) {
                if (v is android.widget.TextView) texts += v.text.toString()
                if (v is android.view.ViewGroup) (0 until v.childCount).forEach { collect(v.getChildAt(it)) }
            }
            collect(activity.window.decorView)
            assertTrue(texts.joinToString("|"), texts.any { it.startsWith("지난 2회 평균보다 시간당") && it.contains("9시 출발") })
            assertEquals("이번 운행이 이력에 한 줄로 더해진다", 3, RunStore.history.size)
            shot(activity, "08-settlement-compare")
            controller.pause().stop().destroy()
        } finally {
            java.util.TimeZone.setDefault(zone)
        }
    }
}
