package app.nextstop.drive

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import app.nextstop.core.time.Clock
import app.nextstop.core.time.ManualClock
import app.nextstop.core.vehicle.Gear
import app.nextstop.core.vehicle.VehicleEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
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
 * 시연 영상 예비 경로: 에뮬레이터 없이 시연 흐름의 장면을 그리고, 장면마다 길이·자막을 `scenes.tsv`로 남긴다.
 * `scripts/demo/render-video.sh`가 이것으로 mp4를 만든다(CI `video` 워크플로).
 * 평소 테스트에서는 건너뛴다 — `-Dnextstop.video=<폴더>`가 있을 때만 돈다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-xhdpi")
class DemoVideoScenesTest {

    private val outDir: File? = System.getProperty("nextstop.video")?.let { File(it).also(File::mkdirs) }
    private val scenes = StringBuilder()
    private var index = 0

    // 정산 화면이 실제 운행처럼 보이도록 시각을 직접 움직인다(배송지 사이 7분, 정차 90초, 1.6km).
    private val clock = ManualClock(1_793_000_000_000L)
    private var odometerKm = 12_034.0

    @Before
    fun setUp() {
        assumeTrue("영상 장면은 -Dnextstop.video 가 있을 때만 만든다", outDir != null)
        // 주행 제한 중 화면과 같은 정적 완료 장면(D를 넣는 순간 실차에서 보이는 화면)
        CompletionOverlay.forceStatic = true
        RunStore.clock = clock
    }

    @After
    fun tearDown() {
        RunStore.resetRun()
        RunStore.shutdown()
        CompletionOverlay.forceStatic = false
        RunStore.clock = Clock.SYSTEM
    }

    private fun idle(d: Duration) = shadowOf(Looper.getMainLooper()).idleFor(d)

    private fun send(e: VehicleEvent) {
        RunStore.demoSource!!.inject(e)
        idle(Duration.ofMillis(50))
    }

    private fun now() = clock.nowMillis()

    private fun scene(activity: DriveActivity, seconds: Double, caption: String) {
        val root = activity.window.decorView
        idle(Duration.ofMillis(16))
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val name = "scene-%02d.png".format(++index)
        File(outDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        scenes.append(name).append('\t').append(seconds).append('\t').append(caption).append('\n')
    }

    private fun drive(minutes: Long, km: Double) {
        clock.advanceMinutes(minutes)
        odometerKm += km
        send(VehicleEvent.OdometerUpdated(odometerKm, now()))
    }

    private fun deliver(activity: DriveActivity, withScenes: Boolean, n: Int) {
        drive(minutes = 7, km = 1.6)
        send(VehicleEvent.GearChanged(Gear.PARK, now()))
        clock.advanceSeconds(5)
        send(VehicleEvent.DoorChanged(true, now()))
        clock.advanceSeconds(80)
        send(VehicleEvent.DoorChanged(false, now()))
        clock.advanceSeconds(5)
        if (withScenes) scene(activity, 3.0, "도착 · P → 문 열고 배송 → 돌아와 문 닫고")
        send(VehicleEvent.GearChanged(Gear.DRIVE, now()))
        idle(Duration.ofMillis(300))
        scene(activity, if (withScenes) 2.5 else 1.0, if (withScenes) "D를 넣는 순간 1건 완료 — 화면을 만지지 않습니다" else "${n}건째 · P · 문 · D")
        idle(Duration.ofSeconds(3))
        if (withScenes) scene(activity, 3.0, "다음 집이 바로 뜨고, 내비에 목적지가 들어갑니다")
    }

    @Test
    fun `demo video scenes`() {
        val intent = Intent().putExtra(DriveActivity.EXTRA_MODE, "demo").putExtra(DriveActivity.EXTRA_NAVI, false)
        val controller = Robolectric.buildActivity(DriveActivity::class.java, intent).setup()
        val activity = controller.get()
        idle(Duration.ofMillis(100))
        scene(activity, 5.0, "출발 전 정차 상태 · 배송지 순서를 정리하고 줄어든 거리를 보여 줍니다")

        send(VehicleEvent.OdometerUpdated(odometerKm, now()))
        RunStore.startRun()
        send(VehicleEvent.GearChanged(Gear.DRIVE, now()))
        idle(Duration.ofSeconds(2))
        scene(activity, 4.0, "주행 중엔 다음 집과 남은 건수 두 줄만 · 버튼 없음")

        deliver(activity, withScenes = true, n = 1)

        // 문을 열지 않은 정차(신호 대기)는 세지 않는다
        drive(minutes = 2, km = 0.5)
        send(VehicleEvent.GearChanged(Gear.PARK, now()))
        clock.advanceSeconds(40)
        send(VehicleEvent.GearChanged(Gear.DRIVE, now()))
        idle(Duration.ofSeconds(2))
        scene(activity, 3.0, "문을 열지 않은 정차(신호 대기)는 완료로 치지 않습니다")
        assertEquals(1, RunStore.snapshot()!!.delivered)

        for (n in 2..5) deliver(activity, withScenes = false, n = n)
        assertEquals(5, RunStore.snapshot()!!.delivered)
        idle(Duration.ofSeconds(1))
        scene(activity, 6.0, "끝나면 자동 완료 건수와 연료비를 뺀 시간당 실수익")
        File(outDir, "scenes.tsv").writeText(scenes.toString())
        controller.pause().stop().destroy()
    }
}
