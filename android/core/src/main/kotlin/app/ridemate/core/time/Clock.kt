package app.ridemate.core.time

/**
 * Time source for the guardian and RideWatch. Countdowns and deadlines use [elapsedMs] (monotonic, like
 * `SystemClock.elapsedRealtime()`, which keeps counting in deep sleep); [wallMs] is only for ordering ride-app posts
 * by their post time and for rebasing persisted countdowns after a reboot.
 */
interface Clock {
    fun elapsedMs(): Long
    fun wallMs(): Long
}

/** JVM fallback. The app wires `SystemClock.elapsedRealtime()` and `System.currentTimeMillis()` instead. */
object JvmClock : Clock {
    private val origin = System.nanoTime()
    override fun elapsedMs(): Long = (System.nanoTime() - origin) / 1_000_000
    override fun wallMs(): Long = System.currentTimeMillis()
}

/** A clock moved by hand; both readings advance together. */
class ManualClock(private var elapsed: Long = 0, private var wall: Long = DEFAULT_WALL_MS) : Clock {
    override fun elapsedMs(): Long = elapsed
    override fun wallMs(): Long = wall

    fun advanceBy(ms: Long) {
        require(ms >= 0) { "time only moves forward" }
        elapsed += ms
        wall += ms
    }

    /** Simulates a reboot: the monotonic clock restarts at [elapsedAfter] while wall time keeps going. */
    fun reboot(downtimeMs: Long, elapsedAfter: Long = 0) {
        wall += downtimeMs
        elapsed = elapsedAfter
    }

    companion object {
        /** 28 Oct 2026 10:00 IST, so test logs read like real ones. */
        const val DEFAULT_WALL_MS = 1_793_163_600_000L
    }
}

/** One reading of both clocks, so a transition records consistent times. */
data class Moment(val elapsedMs: Long, val wallMs: Long)

fun Clock.now(): Moment = Moment(elapsedMs(), wallMs())
