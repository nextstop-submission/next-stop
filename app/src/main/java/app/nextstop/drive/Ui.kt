package app.nextstop.drive

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 화면 공통 색·크기. 차량 화면 기준으로 크게, 고대비로. */
object Palette {
    val BG = Color.parseColor("#0E1116")
    val CARD = Color.parseColor("#1A1F27")
    val TEXT = Color.parseColor("#F5F7FA")
    val SUB = Color.parseColor("#9AA4B2")
    val ACCENT = Color.parseColor("#2ED47A")
    val WARN = Color.parseColor("#FFB020")
    val DIM = Color.parseColor("#3A4350")
    val ROUTE_OLD = Color.parseColor("#5B6573")
}

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

fun Context.label(sizeSp: Float, color: Int = Palette.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        includeFontPadding = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

fun Context.pill(text: String, filled: Boolean = false, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        setTextColor(if (filled) Palette.BG else Palette.TEXT)
        background = GradientDrawable().apply {
            cornerRadius = dp(36f).toFloat()
            setColor(if (filled) Palette.ACCENT else Palette.CARD)
            if (!filled) setStroke(dp(2f), Palette.DIM)
        }
        minHeight = dp(84f)
        minWidth = dp(180f)
        setPadding(dp(28f), 0, dp(28f), 0)
        setOnClickListener { onClick() }
    }

/** [−] 값 [+] 조정기. 정차 중 화면에서만 쓴다. */
fun Context.stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit): LinearLayout =
    row(
        pill("−", onClick = onMinus).apply { minWidth = dp(84f) },
        label(26f).apply { text = value },
        pill("+", onClick = onPlus).apply { minWidth = dp(84f) },
        spacingDp = 10f,
    )

fun Context.row(vararg children: View, spacingDp: Float = 16f): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        children.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = dp(spacingDp)
            })
        }
    }

fun Context.column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun roundedBg(color: Int, radiusPx: Float) = GradientDrawable().apply {
    cornerRadius = radiusPx
    setColor(color)
}

fun won(v: Long): String = "%,d원".format(v)
fun km(v: Double): String = "%.1fkm".format(v)
