package app.nextstop.core.run

import app.nextstop.core.checkpoint.CheckpointPhase
import app.nextstop.core.checkpoint.CheckpointState
import app.nextstop.core.checkpoint.CompletionTrigger
import app.nextstop.core.geo.LatLng
import app.nextstop.core.model.Stop
import app.nextstop.core.vehicle.Gear
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * 운행을 다시 만들 수 있는 최소 상태. 시동을 끄거나 앱이 재시작돼도 '몇 번째 집까지 했는지'를 잃지 않기 위함.
 */
data class RunMemento(
    val stops: List<Stop>,
    val state: CheckpointState,
    val records: List<StopRecord>,
    val startedAt: Long?,
    val finishedAt: Long?,
    val odometerStartKm: Double?,
    val odometerLastKm: Double?,
    val lastLegOdometerKm: Double?,
)

/**
 * 버전 있는 줄 단위 텍스트 형식(외부 라이브러리 없음). 한 줄 = 한 레코드, 필드는 탭 구분, 문자열은 URL 인코딩.
 * 형식이 다르거나 깨진 입력은 예외 대신 null을 돌려준다(복원 실패 시 새 운행으로 시작).
 */
object RunCodec {
    private const val HEADER = "NEXTSTOP-RUN\t1"

    fun encode(m: RunMemento): String = buildString {
        appendLine(HEADER)
        line(
            "meta", num(m.startedAt), num(m.finishedAt), num(m.odometerStartKm), num(m.odometerLastKm),
            num(m.lastLegOdometerKm), m.state.lastGear.name, m.state.doorOpen.toString(), m.state.totalStops.toString(),
        )
        line(*phaseFields(m.state.phase))
        m.stops.forEach { line("stop", *stopFields(it)) }
        m.records.forEach { r ->
            line(
                "record", r.outcome.name, num(r.parkedAt), r.departedAt.toString(), r.trigger?.name.orEmpty(),
                num(r.odometerAtDepartureKm), num(r.legKm), *stopFields(r.stop),
            )
        }
    }

    fun decode(text: String): RunMemento? = runCatching { decodeOrThrow(text) }.getOrNull()

    private fun decodeOrThrow(text: String): RunMemento {
        val lines = text.lines().filter { it.isNotEmpty() }
        require(lines.firstOrNull() == HEADER) { "unsupported format" }
        var meta: List<String>? = null
        var phase: CheckpointPhase? = null
        val stops = mutableListOf<Stop>()
        val records = mutableListOf<StopRecord>()
        for (raw in lines.drop(1)) {
            val f = raw.split('\t')
            when (f[0]) {
                "meta" -> meta = f
                "phase" -> phase = parsePhase(f)
                "stop" -> stops += parseStop(f, 1)
                "record" -> records += StopRecord(
                    stop = parseStop(f, 7),
                    outcome = StopOutcome.valueOf(f[1]),
                    parkedAt = f[2].toLongOrNull(),
                    departedAt = f[3].toLong(),
                    trigger = f[4].takeIf { it.isNotEmpty() }?.let(CompletionTrigger::valueOf),
                    odometerAtDepartureKm = f[5].toDoubleOrNull(),
                    legKm = f[6].toDoubleOrNull(),
                )
                else -> error("unknown record ${f[0]}")
            }
        }
        val m = requireNotNull(meta)
        val state = CheckpointState(
            phase = requireNotNull(phase),
            totalStops = m[8].toInt(),
            lastGear = Gear.valueOf(m[6]),
            doorOpen = m[7].toBooleanStrict(),
        )
        // 시작 전(Idle)에는 totalStops가 0이다. 시작 후에는 저장된 배송지 수와 같아야 한다(잘린 파일 감지).
        require(state.phase is CheckpointPhase.Idle || state.totalStops == stops.size) { "stop count mismatch" }
        return RunMemento(
            stops = stops,
            state = state,
            records = records,
            startedAt = m[1].toLongOrNull(),
            finishedAt = m[2].toLongOrNull(),
            odometerStartKm = m[3].toDoubleOrNull(),
            odometerLastKm = m[4].toDoubleOrNull(),
            lastLegOdometerKm = m[5].toDoubleOrNull(),
        )
    }

    private fun phaseFields(p: CheckpointPhase): Array<String> = when (p) {
        CheckpointPhase.Idle -> arrayOf("phase", "idle")
        CheckpointPhase.Finished -> arrayOf("phase", "finished")
        is CheckpointPhase.EnRoute -> arrayOf("phase", "enroute", p.stopIndex.toString(), p.armed.toString())
        is CheckpointPhase.Parked -> arrayOf(
            "phase", "parked", p.stopIndex.toString(), p.parkedAt.toString(), p.doorOpen.toString(),
            p.doorCycles.toString(), num(p.firstDoorOpenAt),
        )
    }

    private fun parsePhase(f: List<String>): CheckpointPhase = when (f[1]) {
        "idle" -> CheckpointPhase.Idle
        "finished" -> CheckpointPhase.Finished
        "enroute" -> CheckpointPhase.EnRoute(f[2].toInt(), f[3].toBooleanStrict())
        "parked" -> CheckpointPhase.Parked(f[2].toInt(), f[3].toLong(), f[4].toBooleanStrict(), f[5].toInt(), f[6].toLongOrNull())
        else -> error("unknown phase ${f[1]}")
    }

    /** 출발 전 배송지 목록(직접 추가·편집한 것)과 순서 고정 여부. 앱이 내비 뒤에서 종료돼도 목록이 남게 저장한다. */
    fun encodePlan(stops: List<Stop>, keepOrder: Boolean): String = buildString {
        line("plan", "v1", keepOrder.toString())
        stops.forEach { line("stop", *stopFields(it)) }
    }

    fun decodePlan(text: String): Pair<List<Stop>, Boolean>? = runCatching {
        val lines = text.lines().filter { it.isNotBlank() }.map { it.split("\t") }
        val head = lines.first()
        require(head[0] == "plan" && head[1] == "v1") { "not a plan" }
        lines.drop(1).filter { it[0] == "stop" }.map { parseStop(it, 1) } to head[2].toBooleanStrict()
    }.getOrNull()

    private fun stopFields(s: Stop): Array<String> = arrayOf(
        str(s.id), str(s.label), s.location.latitude.toString(), s.location.longitude.toString(),
        s.parcels.toString(), str(s.poiId), str(s.poiSubId),
    )

    private fun parseStop(f: List<String>, at: Int) = Stop(
        id = unstr(f[at])!!,
        label = unstr(f[at + 1])!!,
        location = LatLng(f[at + 2].toDouble(), f[at + 3].toDouble()),
        parcels = f[at + 4].toInt(),
        poiId = unstr(f[at + 5]),
        poiSubId = unstr(f[at + 6]),
    )

    private fun StringBuilder.line(vararg fields: String) = appendLine(fields.joinToString("\t"))

    private fun num(v: Long?) = v?.toString().orEmpty()
    private fun num(v: Double?) = v?.let { String.format(Locale.ROOT, "%s", it) }.orEmpty()

    /** null은 빈 칸, 빈 문자열은 "%00"으로 구분한다. */
    private fun str(v: String?): String = when {
        v == null -> ""
        v.isEmpty() -> "%00"
        else -> URLEncoder.encode(v, Charsets.UTF_8)
    }

    private fun unstr(v: String): String? = when (v) {
        "" -> null
        "%00" -> ""
        else -> URLDecoder.decode(v, Charsets.UTF_8)
    }
}
