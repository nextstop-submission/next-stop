package app.nextstop.core

import app.nextstop.core.model.DestinationCandidate
import app.nextstop.core.model.PlaceCapture
import app.nextstop.core.time.ManualClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceCaptureTest {

    private val clock = ManualClock(1_767_225_600_000)
    private fun c(name: String, lat: Double, lon: Double, poi: String? = null) = DestinationCandidate(name, null, lat, lon, poi, "0")

    @Test
    fun `takes the destination picked after a search once`() {
        val cap = PlaceCapture(clock)
        assertNull(cap.accept(c("서울역", 37.5547, 126.9707, "p1")), "검색 전에는 받지 않는다")
        cap.arm("서울역")
        assertTrue(cap.armed)
        assertEquals("서울역", cap.accept(c("서울역", 37.5547, 126.9707, "p1"))?.name)
        assertFalse(cap.armed)
        assertNull(cap.accept(c("서울역", 37.5547, 126.9707, "p1")), "한 번 받으면 끝")
    }

    @Test
    fun `ignores an empty destination and the same place read again`() {
        val cap = PlaceCapture(clock)
        cap.arm("서울역")
        assertNull(cap.accept(c("", 0.0, 0.0)), "목적지 없음(0,0)")
        assertTrue(cap.armed, "빈 목적지는 대기를 끝내지 않는다")
        cap.accept(c("서울역", 37.5547, 126.9707, "p1"))
        cap.arm("용산역")
        assertNull(cap.accept(c("서울역", 37.5547, 126.9707, "p1")), "취소 전 캐시로 같은 목적지가 다시 읽힌 경우")
        assertEquals("용산역", cap.accept(c("용산역", 37.5299, 126.9648, "p2"))?.name)
    }

    @Test
    fun `waiting expires after ten minutes`() {
        val cap = PlaceCapture(clock)
        cap.arm("서울역")
        clock.advanceMinutes(11)
        assertFalse(cap.armed)
        assertNull(cap.accept(c("서울역", 37.5547, 126.9707, "p1")))
    }

    @Test
    fun `a destination already in the navi before the search is not taken as the pick`() {
        val cap = PlaceCapture(clock)
        cap.arm("", expectBaseline = true)
        assertNull(cap.accept(c("집 근처 마트", 37.40, 127.10, "old")), "검색을 열기 직전에 물은 응답 = 원래 있던 목적지")
        assertTrue(cap.armed)
        assertNull(cap.accept(c("집 근처 마트", 37.40, 127.10, "old")), "돌아와서 같은 옛 목적지가 읽히면 받지 않는다")
        assertEquals("서울역", cap.accept(c("서울역", 37.5547, 126.9707, "p1"))?.name)
    }

    @Test
    fun `a late first answer is a real pick, and an empty baseline blocks nothing`() {
        val cap = PlaceCapture(clock)
        cap.arm("", expectBaseline = true)
        assertNull(cap.accept(c("", 0.0, 0.0)), "목적지 없던 내비")
        assertEquals("서울역", cap.accept(c("서울역", 37.5547, 126.9707, "p1"))?.name)

        cap.arm("", expectBaseline = true)
        clock.advanceMinutes(1)
        assertEquals("용산역", cap.accept(c("용산역", 37.5299, 126.9648, "p2"))?.name, "기준 응답이 사라졌으면 2초 뒤 첫 응답은 진짜 고른 곳")
    }
}
