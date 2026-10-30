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
 * 앱이 뒤에 있을 때 1건 완료를 헤드업 알림 한 줄로 알린다.
 *
 * 에뮬레이터 실측(2026-10-30): 목적지를 넘기면 Pleos 내비가 앞으로 나와 앱 화면을 덮는다 → 완료 확인 화면이 보이지 않는다.
 * 그래서 앱이 화면에 없을 때만 표준 Android 알림(움직임 없는 짧은 글자)으로 "✓ n/N 완료 · 다음 집"을 띄운다.
 */
internal class CompletionNotifier(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL, "배송 완료", NotificationManager.IMPORTANCE_HIGH).apply {
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
        const val PLACE_NOTIFICATION_ID = 2
        const val CHANNEL = "checkpoint"
        const val NOTIFICATION_ID = 1
        /** 한 번 보고 지나갈 길이(설계 원칙: 1~2초 안에 파악). 시스템이 헤드업을 내린 뒤에도 알림 목록에 잠깐 남는다. */
        const val TIMEOUT_MS = 8_000L
    }
}
