package app.nextstop.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import app.nextstop.core.vehicle.Gear
import app.nextstop.core.vehicle.VehicleEvent
import app.nextstop.core.vehicle.VehicleEventListener
import app.nextstop.core.vehicle.VehicleSignalSource

/**
 * 시연·테스트용 목 신호. adb에서 차량 신호를 흉내 낸다(Virtual Console이 안 될 때의 대체 경로).
 *
 *   adb shell am broadcast -a app.nextstop.SIGNAL --es gear P
 *   adb shell am broadcast -a app.nextstop.SIGNAL --es door open
 *   adb shell am broadcast -a app.nextstop.SIGNAL --ef odo 12001.5
 *
 * 화면 '시연 패널' 버튼도 같은 경로([inject])를 쓴다.
 *
 * 위조 방지: 보내는 쪽이 [SENDER_PERMISSION](android.permission.DUMP)을 가져야만 받는다.
 * adb shell은 이 권한을 갖지만 일반 앱은 가질 수 없다(보호 수준 signature|privileged|development — 사람이 adb로 직접 부여해야 함).
 * 또 release 빌드는 시연 신호 모드일 때만 이 수신기를 등록한다(RunStore). 차량 신호 모드의 운행 판정은 외부 방송으로 바꿀 수 없다.
 */
class BroadcastSignalSource(private val context: Context) : VehicleSignalSource {
    override val name = "demo-signal"
    private var sink: VehicleEventListener? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            intent.getStringExtra(EXTRA_GEAR)?.let { parseGear(it)?.let { g -> inject(VehicleEvent.GearChanged(g, now())) } }
            intent.getStringExtra(EXTRA_DOOR)?.let { d ->
                when (d.lowercase()) {
                    "open", "1", "true" -> inject(VehicleEvent.DoorChanged(true, now()))
                    "closed", "close", "0", "false" -> inject(VehicleEvent.DoorChanged(false, now()))
                }
            }
            if (intent.hasExtra(EXTRA_ODO)) inject(VehicleEvent.OdometerUpdated(intent.getFloatExtra(EXTRA_ODO, 0f).toDouble(), now()))
        }
    }

    override fun start(listener: VehicleEventListener) {
        sink = MainThreadListener(listener)
        // RECEIVER_EXPORTED는 adb shell(다른 uid)의 방송을 받기 위함. 보내는 쪽 권한으로 다른 앱을 막는다.
        context.registerReceiver(receiver, IntentFilter(ACTION), SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED)
    }

    override fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        sink = null
    }

    fun inject(event: VehicleEvent) {
        sink?.onVehicleEvent(event)
    }

    companion object {
        const val ACTION = "app.nextstop.SIGNAL"
        const val SENDER_PERMISSION = "android.permission.DUMP"
        const val EXTRA_GEAR = "gear"
        const val EXTRA_DOOR = "door"
        const val EXTRA_ODO = "odo"

        fun parseGear(s: String): Gear? = when (s.uppercase()) {
            "P", "PARK" -> Gear.PARK
            "R", "REVERSE" -> Gear.REVERSE
            "N", "NEUTRAL" -> Gear.NEUTRAL
            "D", "DRIVE" -> Gear.DRIVE
            else -> null
        }
    }
}
