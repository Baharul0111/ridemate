package app.ridemate.net

import app.ridemate.util.Clock

/** How a request authenticates. */
enum class AuthMode {
    /** `Authorization: Bearer <Firebase ID token>`; no token → [ApiResult.AuthNeeded] without a request. */
    REQUIRED,

    /** No Authorization header (`/healthz`, `/readyz`). */
    NONE,
}

/**
 * Every endpoint of contracts §1.3–1.4 with its server deadline (§4.18 E4). The phone deadline is the server
 * deadline + 5 s (§4.12); read-screen and reread share the tap's 20 s budget instead ([TapBudget]).
 */
enum class Endpoint(
    val path: String,
    val method: String,
    val serverDeadlineMs: Long,
    val auth: AuthMode = AuthMode.REQUIRED,
    /** True when the call spends the user's "Read fares" tap budget and sends `X-Tap-Deadline-Ms`. */
    val usesTapBudget: Boolean = false,
) {
    READ_SCREEN("v1/read-screen", "POST", 12_000, usesTapBudget = true),
    REREAD("v1/reread", "POST", 12_000, usesTapBudget = true),
    // Day-1 spike (docs/spikes.md): reasoning tokens cannot be switched off, so these got longer server deadlines.
    POINT("v1/point", "POST", 12_000),
    PARSE_NOTIFICATION("v1/parse-notification", "POST", 10_000),
    PLACE_PICK("v1/place-pick", "POST", 10_000),
    EVENT("v1/event", "POST", 5_000),
    ERASE("v1/erase", "POST", 5_000),
    CONFIG_CHECK("v1/config-check", "GET", 5_000),
    OWNER_FCM_TOKEN("v1/owner/fcm-token", "POST", 5_000),
    ONDC_SEARCH("v1/ondc/search", "POST", 5_000),
    ONDC_SELECT("v1/ondc/select", "POST", 5_000),
    ONDC_INIT("v1/ondc/init", "POST", 5_000),
    ONDC_CONFIRM("v1/ondc/confirm", "POST", 5_000),
    ONDC_STATUS("v1/ondc/status", "POST", 5_000),
    ONDC_CANCEL("v1/ondc/cancel", "POST", 5_000),
    HEALTHZ("healthz", "GET", 5_000, auth = AuthMode.NONE),
    READYZ("readyz", "GET", 5_000, auth = AuthMode.NONE),
}

/**
 * Deadline and retry knobs. Production values follow §4.12; tests shrink them to run in milliseconds.
 *
 * @param phoneMarginMs phone deadline = server deadline + this (5 s).
 * @param jitterMinMs/jitterMaxMs the pause before the single retry (1–2 s).
 */
data class ApiTiming(
    val phoneMarginMs: Long = 5_000,
    val jitterMinMs: Long = 1_000,
    val jitterMaxMs: Long = 2_000,
    /** Overrides of [Endpoint.serverDeadlineMs] (tests). */
    val serverDeadlineOverridesMs: Map<Endpoint, Long> = emptyMap(),
) {
    init {
        require(jitterMinMs in 0..jitterMaxMs) { "jitter range" }
    }

    fun serverDeadlineMs(endpoint: Endpoint): Long = serverDeadlineOverridesMs[endpoint] ?: endpoint.serverDeadlineMs

    /** read-screen 17 s alone (but it shares the 20 s tap budget), parse/place-pick 15 s, point 17 s, others 10 s. */
    fun phoneDeadlineMs(endpoint: Endpoint): Long = serverDeadlineMs(endpoint) + phoneMarginMs

    companion object {
        val PRODUCTION = ApiTiming()
    }
}

/**
 * The time budget of one "Read fares" tap (§4.3, §4.12): read-screen and any reread must finish within
 * [TOTAL_MS] of the tap. Create it at the tap with [start] and pass the same instance to both calls.
 */
class TapBudget(val deadlineElapsedMs: Long) {
    fun remainingMs(clock: Clock): Long = deadlineElapsedMs - clock.elapsedRealtimeMs()

    companion object {
        const val TOTAL_MS: Long = 20_000

        fun start(clock: Clock, totalMs: Long = TOTAL_MS): TapBudget = TapBudget(clock.elapsedRealtimeMs() + totalMs)
    }
}
