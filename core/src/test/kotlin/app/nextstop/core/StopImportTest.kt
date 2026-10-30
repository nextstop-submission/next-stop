package app.nextstop.core

import app.nextstop.core.model.DestinationCandidate
import app.nextstop.core.model.StopImport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StopImportTest {

    private fun c(name: String?, lat: Double, lon: Double, poi: String? = null, address: String? = null) =
        DestinationCandidate(name, address, lat, lon, poi, "0")

    @Test
    fun `converts destinations keeping poi ids`() {
        val stops = StopImport.fromDestinations(listOf(c("가", 37.40, 127.10, "p1"), c("나", 37.41, 127.11)))
        assertEquals(listOf("가", "나"), stops.map { it.label })
        assertEquals("p1", stops[0].poiId)
        assertEquals("p1", stops[0].id)
        assertNull(stops[1].poiId)
        assertEquals("nav-37.41000,127.11000", stops[1].id)
    }

    @Test
    fun `merges same poi or nearby points into one stop with more parcels`() {
        val stops = StopImport.fromDestinations(
            listOf(
                c("A동", 37.40000, 127.10000, "p1"),
                c("A동 재방문", 37.40500, 127.10500, "p1"), // 같은 POI
                c("옆 건물", 37.40010, 127.10010), // 약 14m
                c("먼 곳", 37.42, 127.12),
            ),
        )
        assertEquals(2, stops.size)
        assertEquals(3, stops[0].parcels)
        assertEquals(1, stops[1].parcels)
    }

    @Test
    fun `drops invalid coordinates and falls back to address or index for label`() {
        val stops = StopImport.fromDestinations(
            listOf(
                c("무효", Double.NaN, 127.0),
                c("영점", 0.0, 0.0),
                c("범위밖", 95.0, 127.0),
                c(null, 37.40, 127.10, address = " 주소만 "),
                c("  ", 37.45, 127.15),
            ),
        )
        assertEquals(listOf("주소만", "배송지 2"), stops.map { it.label })
    }

    @Test
    fun `limit caps distinct stops`() {
        val many = (0 until 50).map { c("s$it", 37.0 + it * 0.01, 127.0) }
        assertEquals(30, StopImport.fromDestinations(many).size)
        assertEquals(5, StopImport.fromDestinations(many, limit = 5).size)
    }
}
