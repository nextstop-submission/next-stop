package app.nextstop.core.time

fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** 테스트·시연용 수동 시계. */
class ManualClock(private var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now
    fun advanceSeconds(seconds: Long) { now += seconds * 1_000 }
    fun advanceMinutes(minutes: Long) { now += minutes * 60_000 }
    fun set(millis: Long) { now = millis }
}
