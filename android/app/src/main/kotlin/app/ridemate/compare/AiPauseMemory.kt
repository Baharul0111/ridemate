package app.ridemate.compare

import app.ridemate.core.format.IstTime
import app.ridemate.net.ApiResult
import app.ridemate.net.ConfigCheckResponse
import app.ridemate.util.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The server's last "AI reading is paused" (goals 21/23: the budget is used up, the daily cap or the user's quota is
 * reached), from `/v1/config-check` on Home or from an earlier Read fares.
 *
 * While the pause lasts, Read fares goes straight to the price form: no screen-capture consent, no capture, no OCR and
 * no upload that the spend meter would only refuse. It lasts until the server's `resume_at_ist` (the next such time
 * in IST), or [holdWithoutResumeMs] when the server gave none. Only AI reading pauses; the guardian keeps working.
 *
 * "Try anyway": a second Read fares tap within [tryAnywayWindowMs] of one that was sent to the price form runs the
 * read for real (the server may have resumed early); its answer renews the pause or clears it. Thread-safe.
 */
class AiPauseMemory(
    private val clock: Clock,
    private val holdWithoutResumeMs: Long = HOLD_WITHOUT_RESUME_MS,
    private val tryAnywayWindowMs: Long = TRY_ANYWAY_WINDOW_MS,
) {
    /** An active pause; [resumeAtIst] is the server's "HH:mm" when it gave one. */
    data class Pause(val untilWallMs: Long, val resumeAtIst: String?)

    private var pause: Pause? = null
    private var sentToFormAtElapsedMs: Long? = null

    /** The server said AI reading is paused (until [resumeAtIst], "HH:mm" IST, when known). */
    @Synchronized
    fun paused(resumeAtIst: String?) {
        val now = clock.wallMs()
        val resume = resumeAtIst?.trim()?.takeIf { parse(it) != null }
        val until = resume?.let { nextIstOccurrence(it, now) } ?: (now + holdWithoutResumeMs)
        pause = Pause(until, resume)
        sentToFormAtElapsedMs = null
    }

    /** AI reading works again (a read went through, or config-check says it is on). */
    @Synchronized
    fun resumed() {
        pause = null
        sentToFormAtElapsedMs = null
    }

    /** The pause still in force, without deciding anything about this tap. */
    @Synchronized
    fun current(): Pause? {
        val p = pause ?: return null
        if (clock.wallMs() >= p.untilWallMs) {
            resumed()
            return null
        }
        return p
    }

    /**
     * The pause this tap should honour (it is then sent to the price form), or null when the read may run: no pause,
     * the pause is over, or this is the "try anyway" tap right after one that was sent to the form.
     */
    @Synchronized
    fun pauseForTap(): Pause? {
        val p = current() ?: return null
        val now = clock.elapsedRealtimeMs()
        val sent = sentToFormAtElapsedMs
        if (sent != null && now - sent in 0..tryAnywayWindowMs) return null
        sentToFormAtElapsedMs = now
        return p
    }

    /** Remembers what an AI call said: a pause (with or without a resume time) or that it worked. */
    fun onAiResult(result: ApiResult<*>) {
        when (result) {
            is ApiResult.Ok -> resumed()
            is ApiResult.AiPaused -> if (result.resumeAtIst != null || result.code in LASTING_CODES) paused(result.resumeAtIst)
            else -> Unit // offline, timeouts, auth: not a pause of AI reading
        }
    }

    /** Remembers what `/v1/config-check` said (Home asks it when it opens and after a sign-in change). */
    fun onConfigCheck(result: ApiResult<ConfigCheckResponse>) {
        when (result) {
            is ApiResult.Ok -> {
                val body = result.body
                when {
                    body.aiEnabled -> resumed()
                    // Kill switch and sign-in have their own checks before any capture.
                    body.reason in LASTING_REASONS || (body.reason !in OWN_CHECKS && body.resumeAtIst != null) -> paused(body.resumeAtIst)
                }
            }
            is ApiResult.AiPaused -> onAiResult(result)
            else -> Unit
        }
    }

    private fun nextIstOccurrence(hhmm: String, nowWallMs: Long): Long {
        val time = parse(hhmm) ?: return nowWallMs + holdWithoutResumeMs
        val now = Instant.ofEpochMilli(nowWallMs).atZone(IstTime.ZONE)
        var at = now.toLocalDate().atTime(time).atZone(IstTime.ZONE)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return at.toInstant().toEpochMilli()
    }

    private fun parse(hhmm: String): LocalTime? = runCatching { LocalTime.parse(hhmm, SERVER_HH_MM) }.getOrNull()

    companion object {
        /** A pause without a resume time is honoured this long before the next tap asks the server again. */
        const val HOLD_WITHOUT_RESUME_MS: Long = 10 * 60_000L

        /** A second tap this soon after one sent to the price form is "try anyway". */
        const val TRY_ANYWAY_WINDOW_MS: Long = 2 * 60_000L

        private val SERVER_HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT)

        /** Error codes that mean a pause that lasts (budget, caps, quota), not a passing hiccup. */
        private val LASTING_CODES = setOf("ai_paused", "quota_exhausted")

        /** config-check reasons that mean a lasting pause. */
        private val LASTING_REASONS = setOf("budget", "day_cap", "tier_pause", "quota")

        /** config-check reasons the read's own pre-check already covers. */
        private val OWN_CHECKS = setOf("signed_out", "anonymous", "kill_switch")
    }
}
