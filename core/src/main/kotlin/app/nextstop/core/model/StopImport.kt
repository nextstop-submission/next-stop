package app.nextstop.core.model

import app.nextstop.core.geo.LatLng
import app.nextstop.core.geo.haversineKm

/** 내비(최근 목적지·즐겨찾기)에서 받은 목적지 1건. SDK 타입과 분리한 앱 내부 표현. */
data class DestinationCandidate(
    val name: String?,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    val poiId: String?,
    val poiSubId: String?,
)

/**
 * 내비 목적지 목록 → 배송지 목록.
 * - 좌표가 유효하지 않으면 버린다.
 * - 같은 POI이거나 [mergeMeters] 이내 좌표면 한 곳으로 합치고 소포 수를 더한다(같은 건물 여러 건).
 * - 최대 [limit]곳.
 */
object StopImport {

    fun fromDestinations(
        candidates: List<DestinationCandidate>,
        limit: Int = 30,
        mergeMeters: Double = 30.0,
    ): List<Stop> {
        var out = emptyList<Stop>()
        for (c in candidates) {
            out = when (val r = StopEdit.add(out, c, limit, mergeMeters)) {
                is StopEdit.AddResult.Added -> r.stops
                is StopEdit.AddResult.Merged -> r.stops
                else -> out
            }
        }
        return out
    }

    /** 좌표가 배송지로 쓸 수 있는 값인지(범위 밖·NaN·0,0 제외). */
    internal fun usable(c: DestinationCandidate): Boolean =
        c.latitude.isFinite() && c.longitude.isFinite() &&
            c.latitude in -90.0..90.0 && c.longitude in -180.0..180.0 &&
            !(c.latitude == 0.0 && c.longitude == 0.0)

    /** 내비 목적지 1건 → 배송지. [index]는 이름이 없을 때 '배송지 N'에 쓴다. */
    internal fun toStop(c: DestinationCandidate, index: Int): Stop {
        val poi = c.poiId?.takeIf { it.isNotBlank() }
        val label = c.name?.trim().takeUnless { it.isNullOrEmpty() }
            ?: c.address?.trim().takeUnless { it.isNullOrEmpty() }
            ?: "배송지 $index"
        return Stop(
            id = poi ?: "nav-%.5f,%.5f".format(java.util.Locale.ROOT, c.latitude, c.longitude),
            label = label,
            location = LatLng(c.latitude, c.longitude),
            poiId = poi,
            poiSubId = c.poiSubId?.takeIf { it.isNotBlank() },
        )
    }

    /** 같은 POI이거나 [mergeMeters] 이내면 같은 곳으로 본다. */
    internal fun sameStop(s: Stop, c: DestinationCandidate, mergeMeters: Double): Boolean {
        val poi = c.poiId?.takeIf { it.isNotBlank() }
        return (poi != null && s.poiId == poi) || haversineKm(s.location, LatLng(c.latitude, c.longitude)) * 1000 <= mergeMeters
    }
}
