package app.nextstop.core.display

import app.nextstop.core.checkpoint.CheckpointEffect
import app.nextstop.core.checkpoint.CompletionTrigger
import app.nextstop.core.run.RunSnapshot

/**
 * 화면 문구 결정. 안드로이드와 분리해 문구·글자 수 제한을 단위 테스트한다.
 *
 * 주행 중(UX 제한) 화면은 두 줄만: 1행 다음 배송지, 2행 남은 건수.
 */
data class DriveCard(
    val headline: String,
    val subline: String,
    /** 정차 중에만 보여 주는 보조 정보. 주행 중에는 항상 null. */
    val detail: String?,
    val hint: String?,
)

/** '문 닫고 D 넣으니 다음 집이 떴다' 장면을 만드는 데 필요한 값. */
data class CompletionCue(
    val completedCount: Int,
    val total: Int,
    val completedLabel: String,
    val nextLabel: String?,
    val trigger: CompletionTrigger,
) {
    val isLast: Boolean get() = nextLabel == null
}

object DriveDisplay {
    const val MAX_HEADLINE_CHARS = 12

    fun card(snapshot: RunSnapshot, restricted: Boolean): DriveCard {
        if (snapshot.isFinished) {
            return DriveCard(
                headline = "오늘 배송 끝",
                subline = "${snapshot.delivered}건 완료",
                detail = if (restricted) null else "정차 후 정산을 확인하세요",
                hint = null,
            )
        }
        val current = snapshot.currentStop
            ?: return DriveCard("운행 대기", "${snapshot.total}건 예정", null, if (restricted) null else "정차 중 '출발'을 누르세요")

        val headline = shorten(current.label)
        val subline = "남은 ${snapshot.remaining}건"
        if (restricted) return DriveCard(headline, subline, null, null)

        val detail = buildString {
            append("${snapshot.done + 1}/${snapshot.total}번째")
            if (current.parcels > 1) append(" · ${current.parcels}개")
            snapshot.upcomingStop?.let { append(" · 그다음 ${shorten(it.label)}") }
        }
        val hint = when {
            snapshot.readyToComplete -> "D를 넣으면 완료 처리됩니다"
            snapshot.isParked && snapshot.doorOpen -> "문을 닫고 D를 넣으면 완료"
            snapshot.isParked -> "문을 열고 배송하세요"
            else -> null
        }
        return DriveCard(headline, subline, detail, hint)
    }

    /** 효과 목록에서 완료 연출 신호를 꺼낸다. 완료가 없으면 null. */
    fun completionCue(before: RunSnapshot?, after: RunSnapshot, effects: List<CheckpointEffect>): CompletionCue? {
        val done = effects.filterIsInstance<CheckpointEffect.StopCompleted>().lastOrNull() ?: return null
        val completed = after.records.lastOrNull()?.stop?.label
            ?: before?.stops?.getOrNull(done.stopIndex)?.label
            ?: ""
        return CompletionCue(
            completedCount = after.delivered,
            total = after.total,
            completedLabel = shorten(completed),
            nextLabel = after.currentStop?.let { shorten(it.label) },
            trigger = done.trigger,
        )
    }

    /** 한눈에 읽히도록 글자 수를 제한한다. 잘린 경우 말줄임표. */
    fun shorten(label: String, max: Int = MAX_HEADLINE_CHARS): String {
        val t = label.trim()
        return if (t.length <= max) t else t.take(max - 1) + "…"
    }
}
