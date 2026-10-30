package app.nextstop.core

import app.nextstop.core.geo.LatLng
import app.nextstop.core.model.DestinationCandidate
import app.nextstop.core.model.StopEdit
import app.nextstop.core.route.RouteOptimizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StopEditTest {

    private fun c(name: String?, lat: Double, lon: Double, poi: String? = null) = DestinationCandidate(name, null, lat, lon, poi, "0")

    private fun addAll(vararg cs: DestinationCandidate) = cs.fold(emptyList<app.nextstop.core.model.Stop>()) { acc, x ->
        when (val r = StopEdit.add(acc, x)) {
            is StopEdit.AddResult.Added -> r.stops
            is StopEdit.AddResult.Merged -> r.stops
            else -> acc
        }
    }

    @Test
    fun `adds a searched place to the end with its poi id`() {
        val r = StopEdit.add(emptyList(), c("서울역", 37.5547, 126.9707, "p-seoul"))
        assertIs<StopEdit.AddResult.Added>(r)
        assertEquals(listOf("서울역"), r.stops.map { it.label })
        assertEquals("p-seoul", r.stop.poiId)
    }

    @Test
    fun `adding the same building again counts one more parcel instead of a new stop`() {
        val one = addAll(c("A동", 37.40, 127.10, "p1"))
        val again = StopEdit.add(one, c("A동", 37.40, 127.10, "p1"))
        assertIs<StopEdit.AddResult.Merged>(again)
        assertEquals(1, again.stops.size)
        assertEquals(2, again.stops[0].parcels)
        val nearby = StopEdit.add(again.stops, c("A동 옆 입구", 37.40010, 127.10010)) // 약 14m
        assertIs<StopEdit.AddResult.Merged>(nearby)
        assertEquals(3, nearby.stops[0].parcels)
    }

    @Test
    fun `rejects unusable coordinates and a full list`() {
        assertEquals(StopEdit.AddResult.Invalid, StopEdit.add(emptyList(), c("영점", 0.0, 0.0)))
        assertEquals(StopEdit.AddResult.Invalid, StopEdit.add(emptyList(), c("범위 밖", 91.0, 127.0)))
        val two = addAll(c("가", 37.40, 127.10), c("나", 37.41, 127.11))
        assertEquals(StopEdit.AddResult.Full, StopEdit.add(two, c("다", 37.42, 127.12), limit = 2))
        // 꽉 찬 목록이라도 이미 있는 곳은 소포 수를 늘릴 수 있다
        assertIs<StopEdit.AddResult.Merged>(StopEdit.add(two, c("가", 37.40, 127.10), limit = 2))
    }

    @Test
    fun `remove move up and parcels edit by id`() {
        val s = addAll(c("가", 37.40, 127.10), c("나", 37.41, 127.11), c("다", 37.42, 127.12))
        assertEquals(listOf("가", "다"), StopEdit.remove(s, s[1].id).map { it.label })
        assertEquals(listOf("가", "다", "나"), StopEdit.moveUp(s, s[2].id).map { it.label })
        assertEquals(s, StopEdit.moveUp(s, s[0].id))
        assertEquals(3, StopEdit.setParcels(s, s[0].id, 3)[0].parcels)
        assertEquals(1, StopEdit.setParcels(s, s[0].id, 0)[0].parcels)
    }

    @Test
    fun `keep order plan uses the user order and reports its distance`() {
        val s = addAll(c("먼 곳", 37.50, 127.10), c("가까운 곳", 37.401, 127.10), c("중간", 37.45, 127.10))
        val start = LatLng(37.40, 127.10)
        val o = RouteOptimizer()
        val kept = o.plan(start, s, keepOrder = true)
        assertEquals(s.map { it.id }, kept.orderedStops.map { it.id })
        assertTrue(kept.keptOrder)
        val best = o.plan(start, s, keepOrder = false)
        assertEquals(listOf("가까운 곳", "중간", "먼 곳"), best.orderedStops.map { it.label })
        assertTrue(best.estimatedKm < kept.estimatedKm)
    }
}
