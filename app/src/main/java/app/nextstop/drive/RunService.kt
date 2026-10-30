package app.nextstop.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.nextstop.R
import app.nextstop.core.display.DriveDisplay
import app.nextstop.core.run.RunSnapshot

/**
 * 운행 중에만 도는 포그라운드 서비스. 하는 일은 '운행 중 · 2/5 · 다음 집 ○○' 고정 알림 하나뿐이다.
 *
 * 에뮬레이터 실측(2026-10-30, docs/probe-results/ci-20261030-0347): 목적지를 넘기면 Pleos 내비가 앞으로 나오고,
 * 그동안 뒤에 있던 앱 프로세스가 종료돼 이후 기어·도어 신호를 놓쳤다. 차량 신호를 받는 RunStore가 같은 프로세스에 있으므로
 * 운행하는 동안 프로세스를 포그라운드로 유지한다. 운행이 끝나거나 초기화되면 멈춘다.
 */
class RunService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification(this, RunStore.snapshot())
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        }.onFailure {
            // 포그라운드로 못 올리면 바로 멈춘다(그대로 두면 시스템이 앱을 강제 종료한다). 운행 자체는 계속된다.
            Log.w(RunStore.TAG, "NS|SERVICE|start failed ${it::class.simpleName}")
            stopSelf()
        }.onSuccess { Log.i(RunStore.TAG, "NS|SERVICE|foreground") }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "run"
        private const val NOTIFICATION_ID = 2

        /** @return 시작 요청이 받아들여졌는가(테스트·로그용). */
        fun start(context: Context): Boolean =
            runCatching { context.startForegroundService(Intent(context, RunService::class.java)) }
                .onFailure { Log.w(RunStore.TAG, "NS|SERVICE|not started ${it::class.simpleName}: ${it.message}") }
                .isSuccess

        fun stop(context: Context) {
            context.stopService(Intent(context, RunService::class.java))
        }

        /** 진행이 바뀌면 고정 알림 글자만 바꾼다(서비스가 돌 때만 보인다). */
        fun update(context: Context, snap: RunSnapshot) {
            val m = context.getSystemService(NotificationManager::class.java) ?: return
            if (m.activeNotifications.none { it.id == NOTIFICATION_ID }) return
            m.notify(NOTIFICATION_ID, notification(context, snap))
        }

        private fun notification(context: Context, snap: RunSnapshot?): Notification {
            val m = context.getSystemService(NotificationManager::class.java)
            m?.createNotificationChannel(
                NotificationChannel(CHANNEL, "운행 중", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "운행하는 동안 진행 상황을 한 줄로 보여 줍니다"
                    setShowBadge(false)
                },
            )
            val text = snap?.let { s ->
                val next = s.currentStop?.let { " · 다음 집 ${DriveDisplay.shorten(it.label)}" } ?: ""
                "${s.delivered}/${s.total}$next"
            } ?: "문 닫고 D를 넣으면 1건 완료"
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, DriveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_done)
                .setContentTitle("다음집 운행 중")
                .setContentText(text)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }
    }
}
