package app.nextstop.core.model

import app.nextstop.core.geo.LatLng

/**
 * 배송지 1곳.
 * [label]은 화면·내비 목적지 이름으로 쓰인다. 실제 주소·이름·연락처를 넣지 않는다(샘플은 가상 데이터).
 */
data class Stop(
    val id: String,
    val label: String,
    val location: LatLng,
    val parcels: Int = 1,
    /** 내비에서 가져온 배송지면 그 POI 식별자(경로 요청에 그대로 넘긴다). 샘플 좌표는 null. */
    val poiId: String? = null,
    val poiSubId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "stop id is blank" }
        require(parcels >= 1) { "parcels must be >= 1" }
    }
}
