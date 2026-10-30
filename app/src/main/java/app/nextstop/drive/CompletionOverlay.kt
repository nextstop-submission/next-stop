package app.nextstop.drive

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.nextstop.core.display.CompletionCue

/**
 * '문 닫고 D 넣으니 다음 집이 떴다' 장면.
 *  1) 화면 전체가 초록으로 번쩍 + 큰 체크
 *  2) 완료 카운트가 n-1 → n 으로 올라가며 튀어 오름
 *  3) 초록이 걷히는 동안 다음 배송지 카드가 오른쪽에서 밀려 들어옴([onReveal]에서 카드 갱신)
 * 전체 약 1.4초. 주행 시작 직전(D 직후, 아직 정차) 순간에 끝나도록 짧게 유지한다.
 */
class CompletionOverlay(context: Context) : FrameLayout(context) {
    private val check = context.label(160f, Palette.BG, bold = true).apply { text = "✓" }
    private val count = context.label(96f, Palette.BG, bold = true)
    private val caption = context.label(40f, Palette.BG)

    init {
        setBackgroundColor(Palette.ACCENT)
        alpha = 0f
        visibility = View.GONE
        isClickable = false
        val box = context.column().apply {
            gravity = Gravity.CENTER
            addView(check)
            addView(count, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(8f) })
            addView(caption, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(16f) })
        }
        addView(box, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private var running: AnimatorSet? = null

    /** 테스트·스냅샷용: 연출을 시작 후 [ms] 시점으로 감는다(프레임 타이밍과 무관하게 같은 장면을 재현). */
    internal fun seekForTest(ms: Long) {
        running?.currentPlayTime = ms
    }

    /** 연출 전체 길이(ms). */
    val totalDurationMs: Long get() = running?.totalDuration ?: 0L

    private var staticEnd: Runnable? = null
    private var currentCue: CompletionCue? = null
    private var currentCard: View? = null
    private var revealOnce: (() -> Unit)? = null
    private var frozen = false

    /** 지금 움직이는 연출이 재생 중인가. */
    val isMoving: Boolean get() = running?.isRunning == true

    /**
     * @param motion false면 움직임 없이 정적 완료 화면만 보여 준다.
     *   Pleos 설계 원칙("주행 중 움직이는 이미지로 시선을 끌지 말 것")에 따라, 플랫폼 CarUxRestrictions가
     *   제한 상태이거나 알 수 없으면 호출부가 false를 넘긴다.
     */
    fun play(cue: CompletionCue, nextCard: View, motion: Boolean, onReveal: () -> Unit) {
        frozen = true
        running?.cancel()
        running = null
        staticEnd?.let(::removeCallbacks)
        frozen = false
        currentCue = cue
        currentCard = nextCard
        var revealed = false
        revealOnce = {
            if (!revealed) {
                revealed = true
                onReveal()
            }
        }
        visibility = View.VISIBLE
        caption.text = if (cue.isLast) "${cue.completedLabel} 완료 · 오늘 끝" else "${cue.completedLabel} 완료"
        count.text = "${cue.completedCount - 1} / ${cue.total}"

        // 정적 경로: 주행 제한 중이거나, 시스템 애니메이션이 꺼진 경우(접근성·개발자 옵션 배율 0).
        // 연출이 즉시 끝나 장면이 사라지지 않도록 같은 정보를 움직임 없이 잠깐 보여 준 뒤 다음 집으로 넘긴다.
        if (!motion || forceStatic || !ValueAnimator.areAnimatorsEnabled()) {
            showStatic()
            return
        }
        val flashIn = ObjectAnimator.ofFloat(this, View.ALPHA, 0f, 1f).setDuration(140)
        val checkPop = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(check, View.SCALE_X, 0.4f, 1f),
                ObjectAnimator.ofFloat(check, View.SCALE_Y, 0.4f, 1f),
            )
            duration = 320
            interpolator = OvershootInterpolator(3f)
        }
        val countUp = ValueAnimator.ofInt(cue.completedCount - 1, cue.completedCount).apply {
            duration = 260
            addUpdateListener { count.text = "${it.animatedValue} / ${cue.total}" }
        }
        val countBounce = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(count, View.SCALE_X, 1f, 1.35f, 1f),
                ObjectAnimator.ofFloat(count, View.SCALE_Y, 1f, 1.35f, 1f),
            )
            duration = 360
        }
        val hold = ValueAnimator.ofFloat(0f, 1f).setDuration(380)
        val flashOut = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0f).setDuration(320)
        val slideIn = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(nextCard, View.TRANSLATION_X, nextCard.width.toFloat().coerceAtLeast(600f), 0f),
                ObjectAnimator.ofFloat(nextCard, View.ALPHA, 0f, 1f),
            )
            duration = 360
            interpolator = OvershootInterpolator(1.2f)
        }
        flashOut.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                revealOnce?.invoke()
            }
        })

        running = AnimatorSet().apply {
            playSequentially(
                flashIn,
                AnimatorSet().apply { playTogether(checkPop, AnimatorSet().apply { playSequentially(countUp, countBounce) }) },
                hold,
                AnimatorSet().apply { playTogether(flashOut, slideIn) },
            )
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    nextCard.translationX = 0f
                    nextCard.alpha = 1f
                    if (frozen) return
                    revealOnce?.invoke()
                    visibility = View.GONE
                }
            })
            start()
        }
    }

    /**
     * 움직이는 연출 도중 주행 제한이 켜지면 즉시 움직임을 멈추고 같은 정보를 정적으로 보여 준다.
     * 이미 다음 집 카드가 나온 뒤라면 연출만 바로 닫는다.
     */
    fun stopMotion() {
        val anim = running ?: return
        if (!anim.isRunning) return
        frozen = true
        anim.cancel()
        running = null
        currentCard?.let {
            it.translationX = 0f
            it.alpha = 1f
        }
        showStatic()
    }

    private fun showStatic() {
        val cue = currentCue ?: return
        alpha = 1f
        check.scaleX = 1f
        check.scaleY = 1f
        count.scaleX = 1f
        count.scaleY = 1f
        count.text = "${cue.completedCount} / ${cue.total}"
        visibility = View.VISIBLE
        staticEnd?.let(::removeCallbacks)
        val end = Runnable {
            staticEnd = null
            revealOnce?.invoke()
            visibility = View.GONE
        }
        staticEnd = end
        postDelayed(end, STATIC_HOLD_MS)
    }

    companion object {
        /** 정적 완료 화면을 보여 주는 시간. 한 번 보고 확인할 수 있는 길이(설계 원칙: 1~2초 안에 파악). */
        const val STATIC_HOLD_MS = 1_200L

        /** JVM 화면 테스트용: 애니메이터 구현에 의존하지 않고 정적 완료 화면 경로를 쓴다. */
        @JvmStatic
        internal var forceStatic = false
    }
}
