package app.nextstop.drive

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.nextstop.R
import app.nextstop.core.display.CompletionCue
import app.nextstop.core.display.DriveDisplay

/**
 * 앱이 뒤에 있을 때 1건 완료를 알림 목록에 한 줄로 남긴다.
 *
 * 에뮬레이터 실측(2026-10-30): 목적지를 넘기면 Pleos 내비가 앞으로 나와 앱 화면을 덮는다 → 앱 화면으로는 완료를 볼 수 없다.
 *
 * 중요도는 IMPORTANCE_LOW(헤드업·소리·진동 없음, 상태 표시줄 아이콘과 알림 목록에만)다(심사 지적 A2·A4).
 * 완료는 정의상 D를 넣는 순간, 즉 출발하는 순간에 일어난다. 이때 내비 지도 위에 헤드업 카드가 8초 겹치면
 * '주행 중 1~2초 안에 파악·시선 끌지 않기' 원칙과 어긋난다. 완료 사실은 운행 중 상시 알림(RunService, 역시 LOW)의
 * 'n/N' 글자와 선택 기능인 음성으로도 전달되므로, 끼어드는 알림은 쓰지 않는다.
 * 알림 채널 중요도는 한 번 만들면 앱이 바꿀 수 없어 새 채널 id를 쓰고 옛 HIGH 채널은 지운다.
 */
internal class CompletionNotifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager?.deleteNotificationChannel(OLD_HIGH_CHANNEL)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL, "배송 완료", NotificationManager.IMPORTANCE_LOW).apply {
                description = "문 닫고 D를 넣어 1건이 완료됐을 때 한 줄로 알립니다"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun show(cue: CompletionCue) {
        val m = manager ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val title = if (cue.isLast) "✓ ${cue.completedCount}/${cue.total} 완료 · 오늘 끝" else "✓ ${cue.completedCount}/${cue.total} 완료"
        val text = cue.nextLabel?.let { "다음 집 · ${DriveDisplay.shorten(it)}" } ?: "정산을 확인하세요"
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, DriveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_done)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        m.notify(NOTIFICATION_ID, n)
    }

    /** 배송지 직접 추가: 내비 검색에서 고른 곳이 추가됐음을 알리고, 누르면 앱 계획 화면으로 돌아온다(정차 중에만 쓰는 기능). */
    fun showPlaceAdded(label: String?, total: Int) {
        val m = manager ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, DriveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_done)
            .setContentTitle(if (label != null) "+ ${DriveDisplay.shorten(label)}" else "배송지를 추가하지 못했습니다")
            .setContentText("오늘 배송지 ${total}곳 · 눌러서 다음집으로")
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        m.notify(PLACE_NOTIFICATION_ID, n)
    }

    companion object {
        /** 알림 id는 앱 안에서 겹치지 않게: 1 = 완료, 2 = 운행 중 상시 알림([RunService.NOTIFICATION_ID]), 3 = 배송지 추가. */
        const val NOTIFICATION_ID = 1
        const val PLACE_NOTIFICATION_ID = 3
        const val CHANNEL = "checkpoint-quiet"
        private const val OLD_HIGH_CHANNEL = "checkpoint"
        /** 한 번 보고 지나갈 길이(설계 원칙: 1~2초 안에 파악). 시스템이 헤드업을 내린 뒤에도 알림 목록에 잠깐 남는다. */
        const val TIMEOUT_MS = 8_000L
    }
}
