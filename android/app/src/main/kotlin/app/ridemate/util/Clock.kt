package app.ridemate.util

import android.content.Context
import android.provider.Settings

/**
 * The two time bases RideMate uses.
 *
 * - [elapsedRealtimeMs] is monotonic, keeps counting in deep sleep and restarts at 0 on reboot. Every countdown,
 *   reminder due time and per-call deadline uses it, so changing the phone's clock never shifts a reminder.
 * - [wallMs] is the UTC wall clock (epoch milliseconds). It is stored next to elapsed times so they can be rebased
 *   after a reboot, and it is what the UI shows (in IST, see [Ist]).
 */
interface Clock {
    /** Monotonic milliseconds since boot (`SystemClock.elapsedRealtime()`). */
    fun elapsedRealtimeMs(): Long

    /** UTC epoch milliseconds (`System.currentTimeMillis()`). */
    fun wallMs(): Long
}

/** The real clock. */
object SystemClocks : Clock {
    override fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()
    override fun wallMs(): Long = System.currentTimeMillis()
}

/**
 * Identifies the current boot so values on the elapsed-realtime base can be detected as stale after a reboot.
 * Two calls in the same boot return the same id; a reboot changes it.
 */
fun interface BootIdProvider {
    fun currentBootId(): String
}

/**
 * Uses `Settings.Global.BOOT_COUNT` (API 24+). If a vendor hides it, falls back to the boot instant rounded to
 * 10 s (wall − elapsed), which is stable within a boot unless the wall clock is changed by more than that.
 */
class AndroidBootIdProvider(private val context: Context, private val clock: Clock = SystemClocks) : BootIdProvider {
    override fun currentBootId(): String {
        val count = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()
        if (count != null) return "count:$count"
        val bootInstant = clock.wallMs() - clock.elapsedRealtimeMs()
        return "boot:${bootInstant / 10_000}"
    }
}
