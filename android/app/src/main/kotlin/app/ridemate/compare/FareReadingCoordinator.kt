package app.ridemate.compare

import android.content.Context
import android.widget.Toast
import app.ridemate.R
import app.ridemate.bubble.BubbleStatus
import app.ridemate.bubble.BubbleSurface
import app.ridemate.bubble.StatusKind
import app.ridemate.bubble.TapIds
import app.ridemate.capture.ConsentResult
import app.ridemate.capture.FrameImage
import app.ridemate.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** The latest tap's outcome for a leg, so RideMate's own screens can show it (manual form, messages). */
data class LastRead(val leg: ActiveLeg?, val result: ReadFaresResult, val atWallMs: Long)

/** Gets screen-capture consent for fare reading (production: `CaptureManager.obtainConsent(FARE_READING)`). */
fun interface FareConsent {
    suspend fun ensure(): ConsentResult
}

/**
 * Glue between the bubble's (or notification's) "Read fares" and [ReadFaresUseCase]: finds the leg on screen, asks
 * for consent once per comparison, runs the read and shows the outcome on the bubble. In manual mode it opens
 * RideMate's comparison screen with the price form. Taps arriving while a read runs are ignored.
 */
class FareReadingCoordinator(
    private val context: Context,
    private val readFares: ReadFaresUseCase,
    private val consent: FareConsent,
    private val activeComparison: () -> ActiveComparison,
    private val bubble: () -> BubbleSurface?,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val main: CoroutineDispatcher,
    private val openManualEntry: (ActiveLeg, ReadFaresResult.ManualMode) -> Unit,
    /** Unexpected failures (storage, encoder), reported sanitized; the user still gets a next step. */
    private val onError: (Throwable) -> Unit = {},
) {
    private val running = Mutex()
    private val _lastRead = MutableStateFlow<LastRead?>(null)

    /** The most recent tap's outcome (null before the first). */
    val lastRead: StateFlow<LastRead?> = _lastRead.asStateFlow()

    /** Fire-and-forget entry point for `BubbleActions` (READ_FARES); the tap time is taken now. */
    fun onReadFaresTap(tapId: String): Job {
        val tappedAt = clock.elapsedRealtimeMs()
        return scope.launch { readFares(tapId, tappedAt) }
    }

    /**
     * One "Read fares" tap. Returns null when another read is still running (the tap is ignored), else the outcome
     * (already shown on the bubble).
     */
    suspend fun readFares(tapId: String, tappedAtElapsedMs: Long): ReadFaresResult? {
        if (!running.tryLock()) return null
        var leg: ActiveLeg? = null
        try {
            return readLocked(tapId, tappedAtElapsedMs) { leg = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never leave the bubble spinning: report (sanitized) and say what to do next.
            onError(e)
            return ReadFaresResult.CaptureFailed.also { present(leg, it) }
        } finally {
            running.unlock()
        }
    }

    private suspend fun readLocked(tapId: String, tappedAtElapsedMs: Long, found: (ActiveLeg) -> Unit): ReadFaresResult {
        val leg = activeComparison().current()
        if (leg == null) {
            present(null, ReadFaresResult.NoActiveComparison)
            return ReadFaresResult.NoActiveComparison
        }
        found(leg)
        readFares.precheck(leg.provider)?.let { manual ->
            present(leg, manual)
            return manual
        }
        showWorking()
        var tapAt = tappedAtElapsedMs
        when (val answer = consent.ensure()) {
            is ConsentResult.Granted -> if (answer.fresh && answer.atElapsedMs > tappedAtElapsedMs) {
                // Consent was just given: the user's "Start now" tap starts the read (I6 counts from that tap), once
                // the system dialog has left the screen. A reused projection keeps the user's own Read fares tap.
                delay(CONSENT_DIALOG_SETTLE_MS)
                tapAt = answer.atElapsedMs
            }
            ConsentResult.Denied, ConsentResult.NoAnswer -> return ReadFaresResult.NeedsConsent.also { present(leg, it) }
            ConsentResult.Failed -> return ReadFaresResult.CaptureFailed.also { present(leg, it) }
        }
        val result = readFares.read(ReadFaresRequest(leg.sessionId, leg.legId, leg.provider, tapId, tapAt, leg.tripKm))
        present(leg, result)
        return result
    }

    /**
     * A screenshot shared to RideMate (FLAG_SECURE fallback). The read runs in the background; returns the message
     * to show right away. Takes ownership of [frame].
     */
    fun onSharedScreenshot(frame: FrameImage): String {
        scope.launch {
            frame.use {
                var leg: ActiveLeg? = null
                val result = try {
                    leg = activeComparison().current()
                    val current = leg
                    if (current == null) {
                        ReadFaresResult.NoActiveComparison
                    } else {
                        readFares.readShared(
                            ReadFaresRequest(current.sessionId, current.legId, current.provider, TapIds.newTapId(), frame.capturedAtElapsedMs, current.tripKm),
                            frame,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                    ReadFaresResult.CaptureFailed
                }
                present(leg, result)
            }
        }
        return context.getString(R.string.capture_share_reading)
    }

    private suspend fun showWorking() = withContext(main) {
        bubble()?.setReaderStatus(BubbleStatus(context.getString(R.string.bubble_status_reading), StatusKind.WORKING), busy = true)
    }

    /**
     * Shows the outcome on the bubble. Without a bubble on screen (the tap came from the notification because the
     * ride app hides overlays, or a screenshot was shared) the outcome is shown as a toast, so a tap never ends
     * silently; [lastRead] carries it for the notification too.
     */
    private suspend fun present(leg: ActiveLeg?, result: ReadFaresResult) {
        _lastRead.value = LastRead(leg, result, clock.wallMs())
        val status = FareReadingMessages.status(context, result)
        withContext(main) {
            val surface = bubble()
            if (surface != null && surface.isShowing) {
                surface.setReaderStatus(status, busy = false)
            } else {
                Toast.makeText(context, status.text, Toast.LENGTH_LONG).show()
            }
            if (leg != null && result is ReadFaresResult.ManualMode) openManualEntry(leg, result)
        }
    }

    companion object {
        /** Time for Android's consent dialog to leave the screen before the first frame. */
        const val CONSENT_DIALOG_SETTLE_MS: Long = 450
    }
}
