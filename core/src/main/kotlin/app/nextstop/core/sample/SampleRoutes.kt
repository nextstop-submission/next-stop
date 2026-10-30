package app.nextstop.core.sample

import app.nextstop.core.geo.LatLng
import app.nextstop.core.model.Stop

/**
 * 시연용 가상 배송지. 좌표는 임의의 신도시 업무지구 일대 격자점이며 실제 주소·건물·개인과 무관하다.
 * 이름은 '배송지 + 가상 동 이름' 형식으로만 쓴다.
 */
object SampleRoutes {
    val DEPOT = LatLng(37.4000, 127.1000)

    val DEMO_5: List<Stop> = listOf(
        Stop("s01", "가람동 1구역", LatLng(37.4060, 127.1150)),
        Stop("s02", "나래동 2구역", LatLng(37.3950, 127.1090), parcels = 2),
        Stop("s03", "다솜동 3구역", LatLng(37.4110, 127.0980)),
        Stop("s04", "라온동 4구역", LatLng(37.3920, 127.1210)),
        Stop("s05", "마루동 5구역", LatLng(37.4030, 127.0890), parcels = 3),
    )

    /** 대량 동선(휴리스틱 경로) 확인용 24곳 격자. */
    val GRID_24: List<Stop> = (0 until 24).map { i ->
        val row = i / 6
        val col = i % 6
        Stop(
            id = "g%02d".format(i + 1),
            label = "격자 %02d".format(i + 1),
            location = LatLng(37.3900 + row * 0.006, 127.0850 + col * 0.007),
        )
    }
}
