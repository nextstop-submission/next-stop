package app.nextstop.core

import app.nextstop.core.display.DriveDisplay
import app.nextstop.core.model.Stop
import app.nextstop.core.nav.RecordingNavigator
import app.nextstop.core.geo.LatLng
import app.nextstop.core.run.DeliveryRun
import app.nextstop.core.sample.SampleRoutes
import app.nextstop.core.time.ManualClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveDisplayTest {

    @Test
    fun `restricted card has exactly two short lines and no extras`() {
        val run = DeliveryRun(SampleRoutes.DEMO_5, RecordingNavigator(), ManualClock())
        run.start()
        val card = DriveDisplay.card(run.snapshot(), restricted = true)
        assertEquals("가람동 1구역", card.headline)
        assertEquals("남은 5건", card.subline)
        assertNull(card.detail)
        assertNull(card.hint)
    }

    @Test
    fun `parked card adds detail line`() {
        val run = DeliveryRun(SampleRoutes.DEMO_5, RecordingNavigator(), ManualClock())
        run.start()
        val card = DriveDisplay.card(run.snapshot(), restricted = false)
        assertEquals("1/5번째 · 그다음 나래동 2구역", card.detail)
    }

    @Test
    fun `long labels are shortened for glanceability`() {
        val long = Stop("x", "아주 긴 가상 배송지 이름 테스트", LatLng(37.0, 127.0))
        val shortened = DriveDisplay.shorten(long.label)
        assertEquals(DriveDisplay.MAX_HEADLINE_CHARS, shortened.length)
        assertTrue(shortened.endsWith("…"))
        assertEquals("짧은 이름", DriveDisplay.shorten("  짧은 이름 "))
    }

    @Test
    fun `idle and finished cards`() {
        val run = DeliveryRun(SampleRoutes.DEMO_5, RecordingNavigator(), ManualClock())
        assertEquals("운행 대기", DriveDisplay.card(run.snapshot(), restricted = true).headline)
        val empty = DeliveryRun(emptyList(), RecordingNavigator(), ManualClock())
        empty.start()
        assertEquals("오늘 배송 끝", DriveDisplay.card(empty.snapshot(), restricted = true).headline)
        assertNull(DriveDisplay.card(empty.snapshot(), restricted = true).detail)
    }
}
