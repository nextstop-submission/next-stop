package app.nextstop.drive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** 배송 진행 막대. 칸 하나 = 배송지 하나, 완료 칸은 초록으로 차오른다. */
class ProgressSegments(context: Context) : View(context) {
    private var total = 0
    private var done = 0f
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.DIM }
    private var animator: ValueAnimator? = null

    fun set(done: Int, total: Int, animate: Boolean) {
        this.total = total
        animator?.cancel()
        if (animate && done.toFloat() > this.done) {
            animator = ValueAnimator.ofFloat(this.done, done.toFloat()).apply {
                duration = 600
                addUpdateListener { this@ProgressSegments.done = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            this.done = done.toFloat()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (total <= 0) return
        val gap = context.dp(8f).toFloat()
        val w = (width - gap * (total - 1)) / total
        val r = height / 2f
        for (i in 0 until total) {
            val left = i * (w + gap)
            val rect = RectF(left, 0f, left + w, height.toFloat())
            canvas.drawRoundRect(rect, r, r, off)
            val fill = (done - i).coerceIn(0f, 1f)
            if (fill > 0f) canvas.drawRoundRect(RectF(left, 0f, left + w * fill, height.toFloat()), r, r, on)
        }
    }
}
