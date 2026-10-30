package app.nextstop.drive

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import app.nextstop.core.vehicle.Gear
import app.nextstop.core.vehicle.VehicleEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** 내비가 앱을 덮은 동안(앱이 뒤에 있음) 1건 완료는 알림 한 줄로, 앱이 앞에 있으면 알림 없이 화면 연출로. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-xhdpi")
class CompletionNotifierTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(NotificationManager::class.java)

    @After
    fun tearDown() {
        RunStore.resetRun()
        RunStore.shutdown()
    }

    private fun idle(d: Duration) = shadowOf(Looper.getMainLooper()).idleFor(d)

    private fun send(e: VehicleEvent) {
        RunStore.demoSource!!.inject(e)
        idle(Duration.ofMillis(50))
    }

    private fun deliverOne() {
        val t = System.currentTimeMillis()
        send(VehicleEvent.GearChanged(Gear.PARK, t))
        send(VehicleEvent.DoorChanged(true, t + 1))
        send(VehicleEvent.DoorChanged(false, t + 2))
        send(VehicleEvent.GearChanged(Gear.DRIVE, t + 3))
        idle(Duration.ofSeconds(3))
    }

    @Test
    fun `completion while the app is behind the navi posts one line`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val intent = Intent().putExtra(DriveActivity.EXTRA_MODE, "demo").putExtra(DriveActivity.EXTRA_NAVI, false)
        val controller = Robolectric.buildActivity(DriveActivity::class.java, intent).setup()
        RunStore.startRun()
        send(VehicleEvent.GearChanged(Gear.DRIVE, System.currentTimeMillis()))

        // 앱이 앞에 있을 때: 화면 연출만, 알림 없음
        deliverOne()
        assertEquals(1, RunStore.snapshot()!!.delivered)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())

        // 내비가 앞으로 나와 앱이 뒤로 감
        controller.pause()
        deliverOne()
        assertEquals(2, RunStore.snapshot()!!.delivered)
        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        val extras = posted.single().extras
        assertEquals("✓ 2/5 완료", extras.getString(Notification.EXTRA_TITLE))
        assertTrue(extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("다음 집 · "))
        controller.stop().destroy()
    }

    @Test
    fun `a run keeps a foreground service and stops it on reset`() {
        val intent = Intent().putExtra(DriveActivity.EXTRA_MODE, "demo").putExtra(DriveActivity.EXTRA_NAVI, false)
        val controller = Robolectric.buildActivity(DriveActivity::class.java, intent).setup()
        RunStore.startRun()
        assertTrue("운행을 시작하면 포그라운드 서비스를 요청한다", RunStore.serviceRequested)
        RunStore.resetRun()
        assertFalse("초기화하면 멈춘다", RunStore.serviceRequested)
        controller.pause().stop().destroy()
    }
}
