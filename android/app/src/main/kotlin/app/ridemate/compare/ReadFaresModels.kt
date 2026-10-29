package app.ridemate.compare

import app.ridemate.capture.FrameTiming
import app.ridemate.core.gate.DriverReading
import app.ridemate.core.gate.GatedRow
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.VehicleClass
import app.ridemate.net.ApiResult

/** One "Read fares" tap on [provider]'s price screen for leg [legId] of session [sessionId]. */
data class ReadFaresRequest(
    val sessionId: String,
    val legId: String,
    val provider: Provider,
    /** The user's tap (I1); a fresh one per tap. */
    val tapId: String,
    /** `elapsedRealtime` of the tap: the 20 s budget (§4.12) and the 2 s frame window (I6) start here. */
    val tapAtElapsedMs: Long,
    /** Straight-line trip distance for the ₹/km sanity band; null when unknown. */
    val tripKm: Double?,
)

/** Why AI reading isn't available for this tap; the user types the price instead (goal 21). */
enum class ManualReason {
    SIGNED_OUT,
    ANONYMOUS,
    KILL_SWITCH,

    /** No fare parser for this app in any config layer: Channel B can't check a price. */
    READER_UNAVAILABLE,

    /** A shared screenshot came out blank: the app blocks screenshots too (FLAG_SECURE), so only typing is left. */
    SCREENSHOT_BLANK,
    AI_PAUSED,
    AUTH_NEEDED,
    GOOGLE_REQUIRED,
    RATE_LIMITED,
    OFFLINE,
    TIMEOUT,
    NOT_CONFIGURED,
    SERVER_ERROR,
    ;

    companion object {
        fun of(result: ApiResult<*>): ManualReason = when (result) {
            is ApiResult.Ok -> SERVER_ERROR
            is ApiResult.AiPaused -> AI_PAUSED
            ApiResult.AuthNeeded -> AUTH_NEEDED
            ApiResult.GoogleRequired -> GOOGLE_REQUIRED
            is ApiResult.RateLimited -> RATE_LIMITED
            ApiResult.Offline -> OFFLINE
            ApiResult.Timeout -> TIMEOUT
            ApiResult.NotConfigured -> NOT_CONFIGURED
            is ApiResult.Http -> SERVER_ERROR
        }
    }
}

/** What one tap produced. Every outcome is final: nothing keeps running after [ReadFaresUseCase.read] returns. */
sealed interface ReadFaresResult {
    /** Rows went through the gate and are saved; only VERIFIED rows carry a price. */
    data class Read(
        val provider: Provider,
        val rows: List<GatedRow>,
        val driver: DriverReading,
        val rereadUsed: Boolean,
        val saved: Boolean,
    ) : ReadFaresResult {
        val verifiedCount: Int get() = rows.count { it.status == QuoteStatus.VERIFIED }
        val unreadableCount: Int get() = rows.count { it.status == QuoteStatus.UNREADABLE }
    }

    /**
     * Manual mode: type the price. [resumeAtIst] ("13:30") feeds "AI reading resumes at h:mm a (IST)" when the server
     * knows it; [apiResult] is the failed call (null when no call was made); [suggestedClasses] are the vehicle
     * classes the phone's own reader saw on the screen (classes only, never prices).
     */
    data class ManualMode(
        val provider: Provider,
        val reason: ManualReason,
        val resumeAtIst: String? = null,
        val apiResult: ApiResult<*>? = null,
        val suggestedClasses: List<VehicleClass> = emptyList(),
        /**
         * The server had already said AI reading is paused, so nothing was captured or uploaded for this tap; tapping
         * Read fares again soon after tries anyway ([AiPauseMemory]).
         */
        val pausedEarlier: Boolean = false,
    ) : ReadFaresResult

    /** K3: no ₹/Rs amount and no status keyword for this app; nothing was uploaded. */
    data class NotFareScreen(val provider: Provider) : ReadFaresResult

    /** The ride app blocks screen capture (FLAG_SECURE, never bypassed): share a screenshot or type the price. */
    data class CaptureBlocked(val provider: Provider) : ReadFaresResult

    /** No screen projection is running: Android must ask for consent first. */
    data object NeedsConsent : ReadFaresResult

    /** The frames couldn't be read (projection ended, OCR failed). */
    data object CaptureFailed : ReadFaresResult

    /** I6: the frames weren't within 2 s of the tap, so they were discarded unprocessed. */
    data class TooSlow(val verdict: FrameTiming.Verdict) : ReadFaresResult

    /** The hard 20 s deadline of the tap passed (§4.12); manual entry stays available. */
    data object TimedOut : ReadFaresResult

    /** No comparison is running (or none of its ride apps is open), so there is nothing to read for. */
    data object NoActiveComparison : ReadFaresResult
}
