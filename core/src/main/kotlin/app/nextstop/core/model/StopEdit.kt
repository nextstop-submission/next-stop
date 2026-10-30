package app.nextstop.core.model

/**
 * 출발 전(정차 중) 배송지 목록 편집: 한 곳씩 추가 · 삭제 · 순서 옮기기.
 * 목록은 바꾸지 않고 새 목록을 돌려준다. 같은 곳(같은 POI 또는 [StopImport] 합치기 거리 안)을 다시 추가하면
 * 새 줄을 만들지 않고 그 배송지의 소포 수를 1 늘린다(같은 건물 여러 건).
 */
object StopEdit {

    sealed interface AddResult {
        /** 새 배송지로 맨 끝에 붙었다. */
        data class Added(val stops: List<Stop>, val stop: Stop) : AddResult
        /** 이미 있는 배송지와 같은 곳이라 소포 수만 늘었다. */
        data class Merged(val stops: List<Stop>, val stop: Stop) : AddResult
        /** 좌표가 유효하지 않다. */
        data object Invalid : AddResult
        /** 최대 개수를 넘는다. */
        data object Full : AddResult
    }

    fun add(stops: List<Stop>, c: DestinationCandidate, limit: Int = 30, mergeMeters: Double = 30.0): AddResult {
        if (!StopImport.usable(c)) return AddResult.Invalid
        val dup = stops.indexOfFirst { StopImport.sameStop(it, c, mergeMeters) }
        if (dup >= 0) {
            val merged = stops[dup].copy(parcels = stops[dup].parcels + 1)
            return AddResult.Merged(stops.toMutableList().also { it[dup] = merged }, merged)
        }
        if (stops.size >= limit) return AddResult.Full
        val stop = StopImport.toStop(c, stops.size + 1)
        // 좌표로 만든 id가 우연히 겹치면(같은 좌표, 합치기 거리 0) 뒤에 번호를 붙인다
        val unique = if (stops.any { it.id == stop.id }) stop.copy(id = "${stop.id}#${stops.size + 1}") else stop
        return AddResult.Added(stops + unique, unique)
    }

    fun remove(stops: List<Stop>, id: String): List<Stop> = stops.filterNot { it.id == id }

    /** [id]를 한 칸 앞으로(이미 맨 앞이면 그대로). */
    fun moveUp(stops: List<Stop>, id: String): List<Stop> {
        val i = stops.indexOfFirst { it.id == id }
        if (i <= 0) return stops
        return stops.toMutableList().also { val t = it[i - 1]; it[i - 1] = it[i]; it[i] = t }
    }

    /** 소포 수 바꾸기(1 미만은 1). */
    fun setParcels(stops: List<Stop>, id: String, parcels: Int): List<Stop> =
        stops.map { if (it.id == id) it.copy(parcels = parcels.coerceAtLeast(1)) else it }
}
