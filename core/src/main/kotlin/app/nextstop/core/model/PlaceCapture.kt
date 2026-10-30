package app.nextstop.core.model

import app.nextstop.core.time.Clock

/**
 * 배송지 직접 추가 2단계: 내비 장소 검색에서 사용자가 고른 목적지를 '한 번만' 배송지 후보로 받는다.
 *
 * 흐름: 앱이 검색 화면을 열 때 [arm] → 내비 목적지가 바뀌거나(onDestinationChanged) 앱으로 돌아오면 목적지를 묻고
 * → [accept]가 받을 후보를 돌려준다. 받은 뒤에는 앱이 경로를 취소하므로 내비 목적지는 비어 있어야 정상이다.
 * 그래도 같은 목적지가 다시 읽히면(취소 전 캐시 등) [lastCaptured]와 같다고 보고 무시한다 — 소포 수가 잘못 늘지 않게.
 *
 * 내비에 이미 목적지가 있던 채로 검색을 열면, 돌아왔을 때 그 옛 목적지를 새로 고른 곳으로 착각할 수 있다.
 * 그래서 [arm]에 `expectBaseline`을 주면 대기 직후 [baselineMillis] 안에 오는 첫 응답은 '원래 있던 목적지'로만 적어 두고 받지 않는다
 * (앱은 검색 화면을 열기 직전에 목적지를 한 번 묻는다. 비어 있으면 내비가 좌표 0,0을 돌려준다 — 2026-10-31 CI 실측).
 */
class PlaceCapture(
    private val clock: Clock,
    private val windowMillis: Long = 10 * 60_000L,
    private val baselineMillis: Long = 2_000L,
) {

    var keyword: String? = null
        private set
    private var armedAt: Long? = null
    private var lastCaptured: DestinationCandidate? = null
    private var baselineUntil: Long? = null
    private var baseline: DestinationCandidate? = null

    val armed: Boolean get() = armedAt?.let { clock.nowMillis() - it <= windowMillis } == true

    fun arm(keyword: String, expectBaseline: Boolean = false) {
        this.keyword = keyword
        armedAt = clock.nowMillis()
        baseline = null
        baselineUntil = if (expectBaseline) clock.nowMillis() + baselineMillis else null
    }

    fun disarm() {
        armedAt = null
        keyword = null
        baselineUntil = null
        baseline = null
    }

    /** 받을 후보면 돌려주고 대기를 끝낸다. 대기 중이 아니거나, 목적지가 비었거나, 방금 받은 것과 같으면 null. */
    fun accept(c: DestinationCandidate): DestinationCandidate? {
        if (!armed) return null
        baselineUntil?.let { until ->
            baselineUntil = null
            if (clock.nowMillis() <= until) {
                baseline = c.takeIf { StopImport.usable(it) }
                return null
            }
        }
        if (!StopImport.usable(c)) return null
        if (baseline?.let { same(it, c) } == true) return null
        val last = lastCaptured
        if (last != null && same(last, c)) return null
        lastCaptured = c
        disarm()
        return c
    }

    private fun same(a: DestinationCandidate, b: DestinationCandidate): Boolean {
        val pa = a.poiId?.takeIf { it.isNotBlank() }
        val pb = b.poiId?.takeIf { it.isNotBlank() }
        if (pa != null && pb != null) return pa == pb
        return kotlin.math.abs(a.latitude - b.latitude) < 1e-6 && kotlin.math.abs(a.longitude - b.longitude) < 1e-6
    }
}
