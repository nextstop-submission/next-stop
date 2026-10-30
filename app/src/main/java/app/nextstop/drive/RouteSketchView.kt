package app.nextstop.drive

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import app.nextstop.core.geo.LatLng
import app.nextstop.core.model.Stop

/**
 * 지도 SDK 없이 동선을 보여 주는 약식 그림. 회색 점선 = 입력 순서, 초록 실선 = 최적 순서.
 * 좌표를 화면에 맞게 정규화만 하므로 축척·지형은 표현하지 않는다(정차 중 계획 화면 전용).
 */
class RouteSketchView(context: Context) : View(context) {
    private var depot: LatLng? = null
    private var input: List<Stop> = emptyList()
    private var optimized: List<Stop> = emptyList()
    private var doneIds: Set<String> = emptySet()

    private val oldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(3f).toFloat()
        color = Palette.ROUTE_OLD
        pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }
    private val newPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(6f).toFloat()
        color = Palette.ACCENT
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT }
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }
    private val depotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.WARN }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.BG
        textAlign = Paint.Align.CENTER
        textSize = context.dp(16f).toFloat()
        isFakeBoldText = true
    }

    fun set(depot: LatLng, input: List<Stop>, optimized: List<Stop>, doneIds: Set<String> = emptySet()) {
        this.depot = depot
        this.input = input
        this.optimized = optimized
        this.doneIds = doneIds
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val d = depot ?: return
        val all = listOf(d) + input.map { it.location }
        val minLat = all.minOf { it.latitude }
        val maxLat = all.maxOf { it.latitude }
        val minLon = all.minOf { it.longitude }
        val maxLon = all.maxOf { it.longitude }
        val pad = context.dp(28f).toFloat()
        val w = width - 2 * pad
        val h = height - 2 * pad
        val spanLat = (maxLat - minLat).coerceAtLeast(1e-6)
        val spanLon = (maxLon - minLon).coerceAtLeast(1e-6)
        val scale = minOf(w / spanLon, h / spanLat)
        val offX = pad + (w - spanLon * scale).toFloat() / 2
        val offY = pad + (h - spanLat * scale).toFloat() / 2
        fun x(p: LatLng) = offX + ((p.longitude - minLon) * scale).toFloat()
        fun y(p: LatLng) = offY + ((maxLat - p.latitude) * scale).toFloat()

        fun path(stops: List<Stop>) = Path().apply {
            moveTo(x(d), y(d))
            stops.forEach { lineTo(x(it.location), y(it.location)) }
        }
        canvas.drawPath(path(input), oldPaint)
        canvas.drawPath(path(optimized), newPaint)

        val r = context.dp(16f).toFloat()
        canvas.drawCircle(x(d), y(d), r, depotPaint)
        optimized.forEachIndexed { i, s ->
            val px = x(s.location)
            val py = y(s.location)
            canvas.drawCircle(px, py, r, if (s.id in doneIds) donePaint else dotPaint)
            canvas.drawText("${i + 1}", px, py - (numPaint.descent() + numPaint.ascent()) / 2, numPaint)
        }
    }
}
