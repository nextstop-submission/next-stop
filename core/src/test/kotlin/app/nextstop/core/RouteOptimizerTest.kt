package app.nextstop.core

import app.nextstop.core.geo.LatLng
import app.nextstop.core.geo.haversineKm
import app.nextstop.core.model.Stop
import app.nextstop.core.route.RouteOptimizer
import app.nextstop.core.sample.SampleRoutes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteOptimizerTest {

    private val optimizer = RouteOptimizer()

    @Test
    fun `haversine matches known distance`() {
        // 위도 1도 ≈ 111.2 km
        val d = haversineKm(LatLng(37.0, 127.0), LatLng(38.0, 127.0))
        assertEquals(111.2, d, 0.2)
        assertEquals(0.0, haversineKm(LatLng(37.5, 127.0), LatLng(37.5, 127.0)), 1e-9)
    }

    @Test
    fun `empty and single stop`() {
        assertTrue(optimizer.optimize(SampleRoutes.DEPOT, emptyList()).orderedStops.isEmpty())
        val one = SampleRoutes.DEMO_5.take(1)
        assertEquals(one, optimizer.optimize(SampleRoutes.DEPOT, one).orderedStops)
    }

    @Test
    fun `points on a line are visited in order from start`() {
        val start = LatLng(37.0, 127.0)
        val stops = listOf(4, 1, 3, 2).map { Stop("p$it", "p$it", LatLng(37.0, 127.0 + it * 0.01)) }
        val plan = optimizer.optimize(start, stops)
        assertEquals(listOf("p1", "p2", "p3", "p4"), plan.orderedStops.map { it.id })
        assertTrue(plan.exact)
    }

    @Test
    fun `exact result matches brute force on small random sets`() {
        val rnd = Random(42)
        repeat(20) {
            val n = rnd.nextInt(2, 8)
            val start = LatLng(37.4, 127.1)
            val stops = (0 until n).map { Stop("r$it", "r$it", LatLng(37.4 + rnd.nextDouble(-0.03, 0.03), 127.1 + rnd.nextDouble(-0.03, 0.03))) }
            val plan = optimizer.optimize(start, stops)
            val best = permutations(stops).minOf { optimizer.estimateKm(start, it) }
            assertEquals(best, plan.estimatedKm, 1e-6)
            assertEquals(stops.toSet(), plan.orderedStops.toSet())
        }
    }

    @Test
    fun `optimized route is never longer than given order`() {
        val plan = optimizer.optimize(SampleRoutes.DEPOT, SampleRoutes.DEMO_5)
        assertTrue(plan.estimatedKm <= optimizer.estimateKm(SampleRoutes.DEPOT, SampleRoutes.DEMO_5) + 1e-9)
    }

    @Test
    fun `heuristic handles larger sets, visits each once and beats shuffled order`() {
        val stops = SampleRoutes.GRID_24.shuffled(Random(7))
        val plan = optimizer.optimize(SampleRoutes.DEPOT, stops)
        assertEquals(false, plan.exact)
        assertEquals(24, plan.orderedStops.distinct().size)
        assertTrue(plan.estimatedKm < optimizer.estimateKm(SampleRoutes.DEPOT, stops))
        assertEquals(plan.estimatedKm, optimizer.estimateKm(SampleRoutes.DEPOT, plan.orderedStops), 1e-6)
    }

    @Test
    fun `detour factor scales distance`() {
        val a = LatLng(37.0, 127.0)
        val b = LatLng(37.01, 127.0)
        assertEquals(haversineKm(a, b) * 1.5, RouteOptimizer(1.5).roadKm(a, b), 1e-9)
    }

    private fun <T> permutations(list: List<T>): List<List<T>> =
        if (list.size <= 1) listOf(list)
        else list.flatMap { head -> permutations(list - head).map { listOf(head) + it } }
}
