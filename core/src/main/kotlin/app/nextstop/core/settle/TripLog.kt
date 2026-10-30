package app.nextstop.core.settle

import app.nextstop.core.run.RunSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 업무용 차량 운행기록 한 줄. 국세청 '업무용승용차 운행기록부' 서식의 운행내역 열 구성을 따른다.
 * 사용자(부서·성명) 열은 앱이 개인정보를 갖지 않으므로 비워 두고 사용자가 직접 채운다.
 * 세무 판단(경비 인정 여부 등)은 하지 않는다.
 */
data class TripLogRow(
    val date: String,
    val odometerBeforeKm: Double,
    val odometerAfterKm: Double,
    val drivenKm: Double,
    val commuteKm: Double,
    val businessKm: Double,
    val note: String,
)

object TripLog {

    val HEADER = listOf(
        "사용일자", "부서", "성명",
        "주행 전 계기판 거리(km)", "주행 후 계기판 거리(km)", "주행거리(km)",
        "출·퇴근용(km)", "일반 업무용(km)", "비고",
    )

    /** Odometer 실측이 없으면 null(추정 거리로 운행기록을 만들지 않는다). */
    fun rowFor(snapshot: RunSnapshot, zone: ZoneId): TripLogRow? {
        val before = snapshot.odometerStartKm ?: return null
        val after = snapshot.odometerLastKm ?: return null
        val started = snapshot.startedAt ?: return null
        val driven = (after - before).coerceAtLeast(0.0)
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd(E)", Locale.KOREAN)
            .format(Instant.ofEpochMilli(started).atZone(zone))
        return TripLogRow(
            date = date,
            odometerBeforeKm = before,
            odometerAfterKm = after,
            drivenKm = driven,
            commuteKm = 0.0,
            businessKm = driven,
            note = "배송 ${snapshot.delivered}건",
        )
    }

    /** 엑셀에서 한글이 깨지지 않도록 UTF-8 BOM을 붙인 CSV. */
    fun toCsv(rows: List<TripLogRow>): String = buildString {
        append('﻿')
        appendLine(HEADER.joinToString(",") { escape(it) })
        rows.forEach { r ->
            appendLine(
                listOf(
                    r.date, "", "",
                    km(r.odometerBeforeKm), km(r.odometerAfterKm), km(r.drivenKm),
                    km(r.commuteKm), km(r.businessKm), r.note,
                ).joinToString(",") { escape(it) },
            )
        }
    }

    private fun km(v: Double) = String.format(Locale.ROOT, "%.1f", v)

    private fun escape(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
