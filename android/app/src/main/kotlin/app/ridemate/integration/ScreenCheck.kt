package app.ridemate.integration

import app.ridemate.capture.CaptureManager
import app.ridemate.capture.CapturePurpose
import app.ridemate.capture.ConsentResult
import app.ridemate.capture.FrameTiming
import app.ridemate.capture.StopReason
import app.ridemate.compare.ReadFaresRequest
import app.ridemate.compare.ReadFaresResult
import app.ridemate.compare.ReadFaresUseCase
import app.ridemate.compare.ShowMeUseCase
import app.ridemate.core.guardian.CancelledKeywords
import app.ridemate.core.guardian.CaptureReason
import app.ridemate.core.model.EtaSource
import app.ridemate.core.mask.PlateHash
import app.ridemate.core.model.Provider
import app.ridemate.core.state.TapId
import app.ridemate.ocr.OcrEngine
import app.ridemate.session.CaptureRequester
import app.ridemate.session.SessionCommands
import app.ridemate.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The guardian's user-tapped captures of a live ride app ([CaptureRequester]):
 * - SHOW_ME → fare reading's [ShowMeUseCase] (fresh consent, one frame, `/v1/point`, an arrow beside the control);
 * - CANCEL_PROOF / CHECK_SCREEN → one frame after a fresh consent ("Android will ask again"); its OCR is checked
 *   against the app's `cancel_cards.cancelled_keywords` (rung 3's capture half). On a driver leg the same frame is
 *   also read through the VerificationGate, and a VERIFIED driver ETA/distance goes to the guardian.
 *
 * Every capture follows the user's tap (I6 counts from it, or from the consent tap). Frames stay in memory and are
 * closed at once; RideMate never acts inside the app.
 */
class ScreenCheck(
    private val capture: () -> CaptureManager,
    private val ocr: () -> OcrEngine,
    private val readFares: () -> ReadFaresUseCase,
    private val showMe: () -> ShowMeUseCase,
    private val commands: () -> SessionCommands,
    private val sessionId: () -> String?,
    private val tripKm: suspend () -> Double?,
    private val pointTarget: (Provider) -> String,
    private val cancelledKeywords: (Provider) -> List<String>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
    private val onError: (Throwable) -> Unit = {},
    private val deadlineMs: Long = DEADLINE_MS,
) : CaptureRequester {
    private val running = Mutex()

    override fun request(legId: String, provider: Provider, reason: CaptureReason, tapId: TapId?) {
        if (provider.isDemo || provider.isNetwork) return
        val tap = tapId ?: return // I1/I6: a capture always follows a recorded user tap
        val tappedAt = clock.elapsedRealtimeMs()
        when (reason) {
            CaptureReason.SHOW_ME -> sessionId()?.let { id -> showMe().onShowMeTap(id, provider, pointTarget(provider), tap.value) }
            CaptureReason.CANCEL_PROOF, CaptureReason.CHECK_SCREEN -> scope.launch { check(legId, provider, reason, tap, tappedAt) }
        }
    }

    /** One check; false when another one was still running (the tap is ignored). Public for tests. */
    suspend fun check(legId: String, provider: Provider, reason: CaptureReason, tap: TapId, tappedAt: Long): Boolean {
        if (!running.tryLock()) return false
        try {
            checkLocked(legId, provider, reason, tap, tappedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        } finally {
            withContext(NonCancellable + main) { capture().stopIfRunningFor(purposeOf(reason), StopReason.ONE_SHOT_DONE) }
            running.unlock()
        }
        return true
    }

    private suspend fun checkLocked(legId: String, provider: Provider, reason: CaptureReason, tap: TapId, tappedAt: Long) {
        val manager = capture()
        var tapAt = tappedAt
        when (val answer = manager.obtainConsent(purposeOf(reason))) {
            is ConsentResult.Granted -> if (answer.fresh && answer.atElapsedMs > tapAt) tapAt = answer.atElapsedMs
            else -> return // denied or failed: the card's "I cancelled it" and the notification proof still work
        }
        // Consent may take the user a while; everything after it runs under the deadline (never hangs).
        val remaining = tapAt + deadlineMs - clock.elapsedRealtimeMs()
        if (remaining <= 0) return
        withTimeoutOrNull(remaining) { readFrame(manager, legId, provider, reason, tap, tapAt) }
    }

    private suspend fun readFrame(manager: CaptureManager, legId: String, provider: Provider, reason: CaptureReason, tap: TapId, tapAt: Long) {
        val frame = manager.captureOnceAndStop(tapAt) ?: return
        frame.use { f ->
            if (FrameTiming.check(tapAt, f.capturedAtElapsedMs, clock.elapsedRealtimeMs(), f.origin) != FrameTiming.Verdict.OK) return
            val text = ocr().recognize(f).tokens.joinToString(" ") { it.text }
            val found = CancelledKeywords.found(text, cancelledKeywords(provider))
            if (reason == CaptureReason.CHECK_SCREEN && !found) {
                val session = sessionId()
                if (session != null) {
                    val read = readFares().readShared(ReadFaresRequest(session, legId, provider, tap.value, tapAt, tripKm()), f)
                    val driver = (read as? ReadFaresResult.Read)?.driver
                    val eta = driver?.etaMin?.takeIf { driver.etaSource == EtaSource.VERIFIED }
                    if (eta != null || driver?.distanceMeters != null) {
                        // Same-driver detection (goal 15): the plate on this verified frame, as a hash only.
                        commands().verifiedDriverReading(provider, eta, driver?.distanceMeters, tap, f.capturedAtElapsedMs, PlateHash.of(text))
                        return
                    }
                }
            }
            commands().captureVerdict(provider, found, tap, f.capturedAtElapsedMs)
        }
    }

    private fun purposeOf(reason: CaptureReason): CapturePurpose = when (reason) {
        CaptureReason.CANCEL_PROOF -> CapturePurpose.CANCEL_PROOF
        CaptureReason.CHECK_SCREEN -> CapturePurpose.DRIVER_ETA
        CaptureReason.SHOW_ME -> CapturePurpose.SHOW_ME
    }

    companion object {
        /** Consent, one frame, OCR and (on a driver leg) one read: never longer than a Read fares tap. */
        const val DEADLINE_MS: Long = 20_000
    }
}
