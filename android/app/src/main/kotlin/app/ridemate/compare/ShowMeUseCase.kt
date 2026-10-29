package app.ridemate.compare

import android.content.Context
import app.ridemate.R
import app.ridemate.auth.AuthState
import app.ridemate.bubble.BubbleStatus
import app.ridemate.bubble.BubbleSurface
import app.ridemate.bubble.StatusKind
import app.ridemate.capture.BlackFrameDetector
import app.ridemate.capture.CaptureManager
import app.ridemate.capture.CapturePurpose
import app.ridemate.capture.ConsentResult
import app.ridemate.capture.FrameImage
import app.ridemate.capture.FrameTiming
import app.ridemate.capture.StopReason
import app.ridemate.core.model.Provider
import app.ridemate.core.ocr.Box
import app.ridemate.core.ocr.GridBox
import app.ridemate.net.ApiClient
import app.ridemate.net.ApiResult
import app.ridemate.net.PointRequest
import app.ridemate.ocr.FrameMasker
import app.ridemate.ocr.ImageTooLargeException
import app.ridemate.ocr.MaskedUpload
import app.ridemate.ocr.OcrEngine
import app.ridemate.ocr.OcrFailedException
import app.ridemate.util.Clock
import app.ridemate.ui.UiFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One "Show me" tap during a guided cancel: point at [target] (`cancel_cards.point_target`) on [provider]'s screen. */
data class ShowMeRequest(
    val sessionId: String,
    val provider: Provider,
    val target: String,
    /** The user's tap (I1); a fresh one per tap. */
    val tapId: String,
    /** `elapsedRealtime` of the tap; I6 and the deadline count from here (or from the consent tap). */
    val tapAtElapsedMs: Long,
)

/** What one "Show me" tap did. Anything but [Shown] leaves the instruction card as the guide. */
sealed interface ShowMeResult {
    /** The arrow is drawn beside [target] (screen pixels). */
    data class Shown(val target: Box) : ShowMeResult

    /** The model found no such control on the screen. */
    data object NotFound : ShowMeResult

    /** The control was found but the arrow can't sit beside it without covering it (or the overlay is refused). */
    data object NoRoom : ShowMeResult

    /** The server has grounding switched off (day-1 spike): the integration should hide Show me. */
    data object Unsupported : ShowMeResult

    /** AI isn't available (signed out, anonymous, kill switch, paused, offline…); [resumeAtIst] when known. */
    data class Unavailable(val resumeAtIst: String? = null) : ShowMeResult

    data object NeedsConsent : ShowMeResult

    data object CaptureFailed : ShowMeResult

    /** The app hides its screen from capture (FLAG_SECURE): never bypassed. */
    data class CaptureBlocked(val provider: Provider) : ShowMeResult

    /** I6: the frame wasn't within 2 s of the tap, so it was discarded unprocessed. */
    data class TooSlow(val verdict: FrameTiming.Verdict) : ShowMeResult

    data object TimedOut : ShowMeResult
}

/** The optional, one-frame capture "Show me" uses (production: [CaptureManager] with [CapturePurpose.SHOW_ME]). */
interface OneShotCapture {
    /** Android's consent for this one capture ("Android will ask again"). */
    suspend fun consent(): ConsentResult

    /** Hides RideMate's overlays, grabs one frame and stops the projection at once. */
    suspend fun captureOnceAndStop(tapAtElapsedMs: Long): FrameImage?

    /** Stops this capture's projection if it is still running (deadline hit between consent and frame). */
    fun abandon()

    companion object {
        fun of(capture: CaptureManager): OneShotCapture = object : OneShotCapture {
            override suspend fun consent(): ConsentResult = capture.obtainConsent(CapturePurpose.SHOW_ME)
            override suspend fun captureOnceAndStop(tapAtElapsedMs: Long): FrameImage? = capture.captureOnceAndStop(tapAtElapsedMs)
            override fun abandon() = capture.stopIfRunningFor(CapturePurpose.SHOW_ME, StopReason.ONE_SHOT_DONE)
        }
    }
}

/** The screen size in pixels the pointer is placed in (the captured frame mirrors it). */
data class ScreenSize(val width: Int, val height: Int)

/**
 * "Show me" (§4.7 step 3, §4.10): on the user's tap, Android asks to share the screen (a fresh consent, "Android
 * will ask again"), one frame is captured with RideMate's overlays hidden and the projection stops at once; the frame
 * is OCR'd only to find PII, masked (status bar included), and sent to `/v1/point`; the returned box (numbers only,
 * I5) becomes an arrow drawn BESIDE the control, never on it. RideMate never taps anything: the user taps Cancel.
 *
 * Never hangs: everything after consent runs under [deadlineMs] from the tap. Outcomes are shown on the bubble (or a
 * toast without one) with what to do next; taps while one runs are ignored.
 */
class ShowMeUseCase(
    private val context: Context,
    private val capture: OneShotCapture,
    private val ocr: OcrEngine,
    private val masker: FrameMasker,
    private val api: ApiClient,
    private val auth: () -> AuthState,
    private val config: FareReadingConfig,
    private val bubble: () -> BubbleSurface?,
    private val screenSize: () -> ScreenSize?,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val deadlineMs: Long = DEADLINE_MS,
    private val consentSettleMs: Long = FareReadingCoordinator.CONSENT_DIALOG_SETTLE_MS,
    private val onError: (Throwable) -> Unit = {},
) {
    private val running = Mutex()

    /** False when Show me can't work for [provider] right now (the integration may hide the action). */
    fun isAvailable(provider: Provider): Boolean = unavailable(provider) == null

    /** Fire-and-forget entry point for `BubbleActions` (SHOW_ME); the tap time is taken now. */
    fun onShowMeTap(sessionId: String, provider: Provider, target: String, tapId: String): Job {
        val tappedAt = clock.elapsedRealtimeMs()
        return scope.launch { show(ShowMeRequest(sessionId, provider, target, tapId, tappedAt)) }
    }

    /** One tap; null when another Show me is still running (the tap is ignored), else the outcome (already shown). */
    suspend fun show(request: ShowMeRequest): ShowMeResult? {
        if (!running.tryLock()) return null
        try {
            val result = try {
                showLocked(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
                ShowMeResult.CaptureFailed
            }
            present(request.provider, result)
            return result
        } finally {
            running.unlock()
        }
    }

    private fun unavailable(provider: Provider): ShowMeResult? = when {
        provider.isNetwork -> ShowMeResult.Unsupported
        !config.aiReaderEnabled(provider) -> ShowMeResult.Unavailable()
        auth() !is AuthState.Google -> ShowMeResult.Unavailable() // `/v1/point` needs Google sign-in (contracts §1.3)
        else -> null
    }

    private suspend fun showLocked(request: ShowMeRequest): ShowMeResult {
        unavailable(request.provider)?.let { return it }
        withContext(main) {
            bubble()?.setReaderStatus(BubbleStatus(context.getString(R.string.show_me_looking), StatusKind.WORKING), busy = true)
        }
        var tapAt = request.tapAtElapsedMs
        when (val answer = capture.consent()) {
            is ConsentResult.Granted -> if (answer.fresh && answer.atElapsedMs > tapAt) {
                // The "Start now" tap is the user tap the frame must follow (I6), once the dialog has left the screen.
                delay(consentSettleMs)
                tapAt = answer.atElapsedMs
            }
            ConsentResult.Denied, ConsentResult.NoAnswer -> return ShowMeResult.NeedsConsent
            ConsentResult.Failed -> return ShowMeResult.CaptureFailed
        }
        try {
            val remaining = tapAt + deadlineMs - clock.elapsedRealtimeMs()
            if (remaining <= 0) return ShowMeResult.TimedOut
            return withTimeoutOrNull(remaining) { captureAndPoint(request, tapAt) } ?: ShowMeResult.TimedOut
        } finally {
            // The one-frame projection never outlives this tap, whatever happened above.
            withContext(NonCancellable + main) { capture.abandon() }
        }
    }

    private suspend fun captureAndPoint(request: ShowMeRequest, tapAt: Long): ShowMeResult {
        val frame = capture.captureOnceAndStop(tapAt) ?: return ShowMeResult.CaptureFailed
        return frame.use { f ->
            val verdict = FrameTiming.check(tapAt, f.capturedAtElapsedMs, clock.elapsedRealtimeMs(), f.origin)
            if (verdict != FrameTiming.Verdict.OK) return@use ShowMeResult.TooSlow(verdict)
            val tokens = try {
                ocr.recognize(f)
            } catch (e: OcrFailedException) {
                return@use ShowMeResult.CaptureFailed
            }
            val blank = f.suspectedSecure && tokens.tokens.none { BlackFrameDetector.inContentBand(it.box.centerY, tokens.height) }
            if (blank) return@use ShowMeResult.CaptureBlocked(request.provider)
            // Non-cancellable so a deadline can't drop the masked copy before `use` owns (and recycles) it.
            val upload = try {
                withContext(cpu + NonCancellable) { masker.maskForUpload(f, tokens) }
            } catch (e: ImageTooLargeException) {
                return@use ShowMeResult.CaptureFailed
            }
            upload.use { point(request, it) }
        }
    }

    private suspend fun point(request: ShowMeRequest, upload: MaskedUpload): ShowMeResult {
        val response = api.point(PointRequest(request.provider, upload.jpeg, request.sessionId, request.target))
        val body = (response as? ApiResult.Ok)?.body
            ?: return ShowMeResult.Unavailable((response as? ApiResult.AiPaused)?.resumeAtIst)
        if (!body.supported) return ShowMeResult.Unsupported
        val grid = GridBox.fromList(body.box0to1000?.map { it.toDouble() }) ?: return ShowMeResult.NotFound
        if (grid.x1 <= grid.x0 || grid.y1 <= grid.y0) return ShowMeResult.NotFound
        val size = screenSize()?.takeIf { it.width > 0 && it.height > 0 } ?: return ShowMeResult.NoRoom
        val target = grid.toPixels(size.width, size.height)
        val description = context.getString(R.string.show_me_pointer_description, FareReadingMessages.name(context, request.provider))
        val shown = withContext(main) { bubble()?.showPointer(target, description) == true }
        return if (shown) ShowMeResult.Shown(target) else ShowMeResult.NoRoom
    }

    private suspend fun present(provider: Provider, result: ShowMeResult) {
        val status = ShowMeMessages.status(context, provider, result)
        withContext(main) {
            val surface = bubble()
            if (surface != null && surface.isShowing) {
                surface.setReaderStatus(status, busy = false)
            } else {
                android.widget.Toast.makeText(context, status.text, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        /** Hard deadline of one tap: consent excluded, capture + OCR + masking + `/v1/point` (phone timeout 17 s). */
        const val DEADLINE_MS: Long = 22_000
    }
}

/** Wording for Show me outcomes (strings_compare.xml). Every problem falls back to the instruction card. */
object ShowMeMessages {
    fun status(context: Context, provider: Provider, result: ShowMeResult): BubbleStatus {
        val app = FareReadingMessages.name(context, provider)
        return when (result) {
            is ShowMeResult.Shown -> BubbleStatus(context.getString(R.string.show_me_shown, app), StatusKind.INFO)
            ShowMeResult.NotFound -> problem(context.getString(R.string.show_me_not_found))
            ShowMeResult.NoRoom -> problem(context.getString(R.string.show_me_no_room))
            ShowMeResult.Unsupported -> problem(context.getString(R.string.show_me_unsupported))
            is ShowMeResult.Unavailable -> problem(
                UiFormat.resumeAtIst(result.resumeAtIst)?.let { context.getString(R.string.show_me_paused_until, it) }
                    ?: context.getString(R.string.show_me_unavailable),
            )
            ShowMeResult.NeedsConsent -> problem(context.getString(R.string.show_me_consent_needed))
            ShowMeResult.CaptureFailed -> problem(context.getString(R.string.show_me_capture_failed))
            is ShowMeResult.CaptureBlocked -> problem(context.getString(R.string.show_me_blocked, app))
            is ShowMeResult.TooSlow, ShowMeResult.TimedOut -> problem(context.getString(R.string.show_me_timed_out))
        }
    }

    private fun problem(text: String) = BubbleStatus(text, StatusKind.PROBLEM)
}
