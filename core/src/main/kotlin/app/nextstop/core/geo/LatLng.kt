package app.nextstop.core.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 좌표. 위치 SDK 없이 샘플 좌표만으로 동선을 계산하기 위한 최소 단위. */
data class LatLng(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }
}

private const val EARTH_RADIUS_KM = 6371.0088

/** 두 점 사이 대권거리(km). */
fun haversineKm(a: LatLng, b: LatLng): Double {
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
