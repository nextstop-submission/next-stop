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
        context.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
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
