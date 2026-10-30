package app.nextstop.drive

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** 계획 화면의 배송지 편집(삭제·순서 고정·옮기기)이 화면 버튼으로 되고, 앱이 종료돼도 남는지. */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-xhdpi")
class PlanEditTest {

    private val intent = Intent().putExtra(DriveActivity.EXTRA_MODE, "demo").putExtra(DriveActivity.EXTRA_NAVI, false)

    @After
    fun tearDown() {
        RunStore.setKeepOrder(false)
        RunStore.useSampleStops()
        RunStore.resetRun()
        RunStore.shutdown()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun buttons(root: View, text: String): List<Button> = when {
        root is Button && root.text.toString() == text -> listOf(root)
        root is ViewGroup -> (0 until root.childCount).flatMap { buttons(root.getChildAt(it), text) }
        else -> emptyList()
    }

    @Test
    fun `stops are removed and reordered from the plan screen and survive process death`() {
        val first = Robolectric.buildActivity(DriveActivity::class.java, intent).setup()
        val root = first.get().window.decorView
        idle()
        val total = RunStore.inputStops.size
        assertEquals("줄마다 삭제 버튼", total, buttons(root, "삭제").size)
        assertTrue("자동 최적화일 때는 옮기기 버튼이 없다", buttons(root, "▲").isEmpty())

        buttons(root, "삭제").first().performClick(); idle()
        assertEquals(total - 1, RunStore.inputStops.size)
        assertEquals(total - 1, buttons(root, "삭제").size)

        buttons(root, "순서: 자동 최적화").single().performClick(); idle()
        assertTrue(RunStore.keepOrder)
        val before = RunStore.plan.orderedStops.map { it.id }
        buttons(root, "▲").first().performClick(); idle()   // 두 번째 줄을 맨 위로
        val after = RunStore.plan.orderedStops.map { it.id }
        assertEquals(listOf(before[1], before[0]) + before.drop(2), after)
        assertTrue(RunStore.plan.keptOrder)
        first.pause().stop().destroy()

        // 시동을 끈 것처럼 프로세스 상태를 버리고 다시 연다 → 편집한 목록·순서 고정이 그대로
        RunStore.simulateProcessDeath()
        assertFalse(RunStore.keepOrder)
        val second = Robolectric.buildActivity(DriveActivity::class.java, intent).setup()
        idle()
        assertTrue(RunStore.keepOrder)
        assertEquals(after, RunStore.plan.orderedStops.map { it.id })
        assertEquals(total - 1, buttons(second.get().window.decorView, "삭제").size)
        second.pause().stop().destroy()
    }

    @Test
    fun `an empty plan cannot start a run`() {
        val c = Robolectric.buildActivity(DriveActivity::class.java, intent).setup()
        idle()
        buttons(c.get().window.decorView, "모두 지우기").single().performClick(); idle()
        assertTrue(RunStore.inputStops.isEmpty())
        assertEquals(0, buttons(c.get().window.decorView, "삭제").size)
        buttons(c.get().window.decorView, "운행 시작").single().performClick(); idle()
        assertNull("배송지 없이 운행이 시작되면 안 된다", RunStore.snapshot())
        c.pause().stop().destroy()
    }
}
