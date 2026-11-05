package app.nextstop.drive

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
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
        // 출발 순간: 전면 연출·지연 없이 같은 2줄 카드의 글자와 진행 막대만 바뀐다(심사 지적 A2)
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
}
