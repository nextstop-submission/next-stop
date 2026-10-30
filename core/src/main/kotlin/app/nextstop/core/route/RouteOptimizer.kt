package app.nextstop.core.route

import app.nextstop.core.geo.LatLng
import app.nextstop.core.geo.haversineKm
import app.nextstop.core.model.Stop

/**
 * 출발점에서 모든 배송지를 한 번씩 도는 열린 경로(복귀 없음) 최적화.
 *
 * 외부 경로 API 없이 직선거리 × [detourFactor]로 도로거리를 근사한다.
 * - 배송지 [EXACT_LIMIT]곳 이하: Held-Karp 동적계획법으로 최적해
 * - 그보다 많으면: 최근접 이웃으로 시작해 2-opt + Or-opt 개선(지역 최적해)
 */
class RouteOptimizer(
    private val detourFactor: Double = DEFAULT_DETOUR_FACTOR,
) {
    init {
        require(detourFactor >= 1.0) { "detourFactor must be >= 1.0" }
    }

    fun optimize(start: LatLng, stops: List<Stop>): RoutePlan {
        if (stops.isEmpty()) return RoutePlan(start, emptyList(), 0.0, exact = true)
        val points = listOf(start) + stops.map { it.location }
        val d = Array(points.size) { i -> DoubleArray(points.size) { j -> roadKm(points[i], points[j]) } }

        val exact = stops.size <= EXACT_LIMIT
        val order = if (exact) heldKarp(d, stops.size) else improve(d, nearestNeighbor(d, stops.size))
        return RoutePlan(start, order.map { stops[it - 1] }, pathLength(d, order), exact)
    }

    /** [keepOrder]면 입력 순서 그대로(사용자가 순서를 고정), 아니면 최적화. */
    fun plan(start: LatLng, stops: List<Stop>, keepOrder: Boolean): RoutePlan =
        if (keepOrder) RoutePlan(start, stops, estimateKm(start, stops), exact = false, keptOrder = true)
        else optimize(start, stops)

    /** 현재 순서를 그대로 둘 때의 추정 거리(최적화 전후 비교용). */
    fun estimateKm(start: LatLng, orderedStops: List<Stop>): Double {
        var prev = start
        var sum = 0.0
        orderedStops.forEach {
            sum += roadKm(prev, it.location)
            prev = it.location
        }
        return sum
    }

    fun roadKm(a: LatLng, b: LatLng): Double = haversineKm(a, b) * detourFactor

    /** 노드 0 = 출발점, 1..n = 배송지. 반환은 방문할 노드 번호(1..n) 순서. */
    private fun heldKarp(d: Array<DoubleArray>, n: Int): List<Int> {
        val full = 1 shl n
        val cost = Array(full) { DoubleArray(n) { Double.POSITIVE_INFINITY } }
        val parent = Array(full) { IntArray(n) { -1 } }
        for (j in 0 until n) cost[1 shl j][j] = d[0][j + 1]
        for (mask in 1 until full) {
            for (j in 0 until n) {
                if (mask and (1 shl j) == 0) continue
                val c = cost[mask][j]
                if (c == Double.POSITIVE_INFINITY) continue
                for (k in 0 until n) {
                    if (mask and (1 shl k) != 0) continue
                    val next = mask or (1 shl k)
                    val candidate = c + d[j + 1][k + 1]
                    if (candidate < cost[next][k]) {
                        cost[next][k] = candidate
                        parent[next][k] = j
                    }
                }
            }
        }
        var last = (0 until n).minBy { cost[full - 1][it] }
        var mask = full - 1
        val reversed = ArrayList<Int>(n)
        while (last != -1) {
            reversed += last + 1
            val p = parent[mask][last]
            mask = mask and (1 shl last).inv()
            last = p
        }
        return reversed.asReversed()
    }

    private fun nearestNeighbor(d: Array<DoubleArray>, n: Int): MutableList<Int> {
        val visited = BooleanArray(n + 1).also { it[0] = true }
        val order = ArrayList<Int>(n)
        var current = 0
        repeat(n) {
            var best = -1
            for (k in 1..n) if (!visited[k] && (best == -1 || d[current][k] < d[current][best])) best = k
            visited[best] = true
            order += best
            current = best
        }
        return order
    }

    private fun improve(d: Array<DoubleArray>, initial: MutableList<Int>): List<Int> {
        var order = initial
        var improved = true
        var rounds = 0
        while (improved && rounds < MAX_ROUNDS) {
            improved = twoOpt(d, order) or orOpt(d, order)
            rounds++
        }
        return order
    }

    /** 경로 구간 뒤집기. 시작점은 고정, 끝점은 열려 있다. */
    private fun twoOpt(d: Array<DoubleArray>, order: MutableList<Int>): Boolean {
        var changed = false
        val path = listOf(0) + order
        val p = path.toMutableList()
        for (i in 1 until p.size - 1) {
            for (k in i + 1 until p.size) {
                val before = d[p[i - 1]][p[i]] + if (k + 1 < p.size) d[p[k]][p[k + 1]] else 0.0
                val after = d[p[i - 1]][p[k]] + if (k + 1 < p.size) d[p[i]][p[k + 1]] else 0.0
                if (after + EPS < before) {
                    p.subList(i, k + 1).reverse()
                    changed = true
                }
            }
        }
        if (changed) {
            order.clear()
            order.addAll(p.drop(1))
        }
        return changed
    }

    /** 1~3개 연속 구간을 다른 위치로 옮기기. */
    private fun orOpt(d: Array<DoubleArray>, order: MutableList<Int>): Boolean {
        val base = pathLength(d, order)
        for (len in 1..3) {
            for (i in 0..order.size - len) {
                val segment = order.subList(i, i + len).toList()
                val rest = order.toMutableList().also { list -> repeat(len) { list.removeAt(i) } }
                for (j in 0..rest.size) {
                    if (j == i) continue
                    val candidate = rest.toMutableList().also { it.addAll(j, segment) }
                    if (pathLength(d, candidate) + EPS < base) {
                        order.clear()
                        order.addAll(candidate)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun pathLength(d: Array<DoubleArray>, order: List<Int>): Double {
        var prev = 0
        var sum = 0.0
        order.forEach {
            sum += d[prev][it]
            prev = it
        }
        return sum
    }

    companion object {
        /** 도심 도로 굴곡 보정 계수(직선 대비). 실측 전 보수적 기본값. */
        const val DEFAULT_DETOUR_FACTOR = 1.3
        const val EXACT_LIMIT = 11
        private const val MAX_ROUNDS = 50
        private const val EPS = 1e-9
    }
}

data class RoutePlan(
    val start: LatLng,
    val orderedStops: List<Stop>,
    val estimatedKm: Double,
    /** true면 최적해 보장(Held-Karp), false면 휴리스틱 결과. */
    val exact: Boolean,
    /** true면 최적화하지 않고 사용자가 정한 순서 그대로. */
    val keptOrder: Boolean = false,
)
