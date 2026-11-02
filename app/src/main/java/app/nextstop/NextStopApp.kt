package app.nextstop

import android.app.ActivityManager
import android.app.Application
import android.util.Log

class NextStopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 직전 프로세스가 왜 끝났는지 한 줄(리허설 C에서 포그라운드 서비스 중에도 앱이 한 번 종료됐다 — 원인을 가리려고)
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()?.let {
                Log.i("NextStop", "NS|EXIT|last reason=${it.reason} importance=${it.importance} pss=${it.pss}kB desc=${it.description}")
            }
        }
    }
}
