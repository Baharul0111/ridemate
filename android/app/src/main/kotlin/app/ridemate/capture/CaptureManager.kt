package app.ridemate.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import app.ridemate.crash.CrashReporter
import app.ridemate.crash.CrashSite
import app.ridemate.util.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Where the capture stands. */
sealed interface CaptureState {
    data object Idle : CaptureState

    data class AwaitingConsent(val purpose: CapturePurpose) : CaptureState

    data class Active(val purpose: CapturePurpose, val startedAtElapsedMs: Long) : CaptureState
}

/** The user's answer on Android's "share your screen" dialog. */
sealed interface ConsentResult {
    /**
     * Projection running. [fresh]: the user just tapped "Start now" on Android's dialog, at [atElapsedMs] (a user tap
     * for I6). Not fresh: the comparison's running projection was reused without asking, [atElapsedMs] is when it
     * started, and the caller's own tap stays the I6 tap.
     */
    data class Granted(val atElapsedMs: Long, val fresh: Boolean = true) : ConsentResult

    data object Denied : ConsentResult

    /** Consent was given but the projection could not start (foreground service missing, Android refused). */
    data object Failed : ConsentResult

    /** No answer arrived (the consent screen was closed without a result). */
    data object NoAnswer : ConsentResult
}

/** Why a projection ended. */
enum class StopReason {
    FARE_READING_DONE,
    ONE_SHOT_DONE,
    SESSION_ENDED,
    STOPPED_BY_SYSTEM,
    ERROR,

    /** No "Read fares" tap for [CaptureManager.FARE_IDLE_STOP_MS]: the user moved on, so fare reading is over. */
    IDLE,
}

/**
 * CaptureManager (§4.2): MediaProjection of the user's own screen, only on the user's tap.
 *
 * - One consent per comparison ([CapturePurpose.FARE_READING]); later optional captures ask again.
 * - Consent comes from [CaptureConsentActivity] (transparent); right after it the session service gains the
 *   `mediaProjection` foreground type ([CaptureForegroundHost]) and the projection starts with one VirtualDisplay +
 *   ImageReader ([ProjectionGateway]). `MediaProjection.Callback.onStop` is registered there and lands in
 *   [onProjectionStopped].
 * - A "Read fares" tap hides RideMate's overlays, grabs 2 frames 400 ms apart and shows the overlays again.
 * - [stop] ends the projection (right after the last fare read) and drops the foreground type.
 * - Safety net: a fare-reading projection nobody reads with for [FARE_IDLE_STOP_MS] stops by itself ([StopReason.IDLE]),
 *   so the `mediaProjection` type never outlives fare reading when the user moves on without Done or Stop.
 *
 * Frames live in memory only. Call [onConsentResult] and [stop] on the main thread.
 */
class CaptureManager(
    private val context: Context,
    private val gateway: ProjectionGateway,
    private val clock: Clock,
    private val crash: CrashReporter? = null,
    /** RideMate's overlays to hide during a grab (the live bubble, when one is shown). */
    private val overlays: () -> CaptureOverlayControl? = { null },
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val launchConsentScreen: (Intent) -> Boolean = { intent -> defaultLaunch(context, intent) },
    /** Runs the idle stop timer; null disables it. */
    private val idleScope: CoroutineScope? = null,
    private val idleStopMs: Long = FARE_IDLE_STOP_MS,
) : FareFrameSource {

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _lastStop = MutableStateFlow<StopReason?>(null)

    /** Why the most recent projection ended (null before the first one). */
    val lastStop: StateFlow<StopReason?> = _lastStop.asStateFlow()

    /** Bound by the session service while it runs (set it in `onCreate`, clear it in `onDestroy`). */
    @Volatile
    var foregroundHost: CaptureForegroundHost? = null

    private val lock = Any()
    private var session: ProjectionSession? = null
    private var pendingConsent: CompletableDeferred<ConsentResult>? = null
    private var pendingPurpose: CapturePurpose? = null
    private var idleJob: Job? = null // guarded by lock
    private val grabMutex = Mutex()

    val isActive: Boolean get() = _state.value is CaptureState.Active

    /** The intent [CaptureConsentActivity] launches. */
    fun consentIntent(): Intent = gateway.consentIntent()

    /**
     * Shows Android's consent dialog (through [CaptureConsentActivity]) and waits for the answer, at most
     * [timeoutMs]. A running projection for [CapturePurpose.FARE_READING] is reused without asking again.
     */
    suspend fun obtainConsent(purpose: CapturePurpose, timeoutMs: Long = CONSENT_TIMEOUT_MS): ConsentResult {
        val deferred: CompletableDeferred<ConsentResult>
        synchronized(lock) {
            val current = _state.value
            if (current is CaptureState.Active && current.purpose == purpose && !purpose.asksAgain) {
                return ConsentResult.Granted(current.startedAtElapsedMs, fresh = false)
            }
            pendingConsent?.complete(ConsentResult.NoAnswer)
            deferred = CompletableDeferred()
            pendingConsent = deferred
            pendingPurpose = purpose
            if (current !is CaptureState.Active) _state.value = CaptureState.AwaitingConsent(purpose)
        }
        val launched = runCatching { launchConsentScreen(Intent(context, CaptureConsentActivity::class.java)) }
            .onFailure { crash?.recordNonFatal(it, CrashSite.CAPTURE) }.getOrDefault(false)
        if (!launched) {
            settleConsent(deferred, ConsentResult.Failed)
            return ConsentResult.Failed
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: ConsentResult.NoAnswer
        settleConsent(deferred, result)
        return result
    }

    private fun settleConsent(deferred: CompletableDeferred<ConsentResult>, result: ConsentResult) {
        synchronized(lock) {
            deferred.complete(result)
            if (pendingConsent === deferred) {
                pendingConsent = null
                pendingPurpose = null
                if (_state.value is CaptureState.AwaitingConsent) _state.value = CaptureState.Idle
            }
        }
    }

    /**
     * The consent dialog's answer (from [CaptureConsentActivity], main thread). On "Start now" the session service
     * gains the `mediaProjection` type first, then the projection starts.
     */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        val (deferred, purpose) = synchronized(lock) { pendingConsent to pendingPurpose }
        if (deferred == null || purpose == null) return // nobody is waiting (stale result): never start a projection
        if (resultCode != Activity.RESULT_OK || data == null) {
            settleConsent(deferred, ConsentResult.Denied)
            return
        }
        // A new consent replaces any running projection (each token makes one VirtualDisplay).
        stopSession(StopReason.ONE_SHOT_DONE, notifyHost = false)
        val host = foregroundHost
        val promoted = runCatching { host?.enterMediaProjectionForeground() == true }
            .onFailure { crash?.recordNonFatal(it, CrashSite.CAPTURE) }.getOrDefault(false)
        if (!promoted) {
            settleConsent(deferred, ConsentResult.Failed)
            return
        }
        val started = runCatching { gateway.start(resultCode, data) { onProjectionStopped() } }
            .onFailure { crash?.recordNonFatal(it, CrashSite.CAPTURE) }.getOrNull()
        if (started == null) {
            runCatching { host?.exitMediaProjectionForeground() }
            settleConsent(deferred, ConsentResult.Failed)
            return
        }
        val now = clock.elapsedRealtimeMs()
        synchronized(lock) {
            session = started
            _state.value = CaptureState.Active(purpose, now)
        }
        armIdleStop()
        settleConsent(deferred, ConsentResult.Granted(now))
    }

    /**
     * Hides RideMate's overlays, grabs two frames [FRAME_GAP_MS] apart and restores the overlays (even on failure or
     * cancellation). The frames belong to the caller.
     */
    override suspend fun captureTwoFrames(tapAtElapsedMs: Long): CaptureOutcome = grabMutex.withLock {
        val active = synchronized(lock) { session } ?: return CaptureOutcome.NotActive
        val overlayControl = overlays()
        try {
            withContext(mainDispatcher) { overlayControl?.hideForCapture() }
            val first = active.grab(maxOf(tapAtElapsedMs, clock.elapsedRealtimeMs()), FIRST_FRAME_WAIT_MS)
                ?: return CaptureOutcome.Failed
            val second = try {
                delay(FRAME_GAP_MS)
                active.grab(first.capturedAtElapsedMs + 1, SECOND_FRAME_WAIT_MS)
            } catch (e: Throwable) {
                first.close()
                throw e
            }
            if (second == null) {
                first.close()
                return CaptureOutcome.Failed
            }
            CaptureOutcome.Frames(first, second)
        } finally {
            withContext(NonCancellable + mainDispatcher) { overlayControl?.restoreAfterCapture() }
            armIdleStop()
        }
    }

    /**
     * One frame for an optional later capture ("Show me", driver ETA, cancel proof), then the projection stops at
     * once. Requires a projection started for that purpose by [obtainConsent].
     */
    suspend fun captureOnceAndStop(tapAtElapsedMs: Long): FrameImage? = grabMutex.withLock {
        val active = synchronized(lock) { session } ?: return null
        val overlayControl = overlays()
        try {
            withContext(mainDispatcher) { overlayControl?.hideForCapture() }
            active.grab(maxOf(tapAtElapsedMs, clock.elapsedRealtimeMs()), FIRST_FRAME_WAIT_MS)
        } finally {
            withContext(NonCancellable + mainDispatcher) {
                overlayControl?.restoreAfterCapture()
                stop(StopReason.ONE_SHOT_DONE)
            }
        }
    }

    /** Ends the projection and drops the `mediaProjection` foreground type. Idempotent. */
    fun stop(reason: StopReason) {
        stopSession(reason, notifyHost = true)
    }

    /**
     * Stops the projection only while it runs for [purpose]: an optional capture abandoned before its one frame (a
     * deadline hit after consent) must not leave its projection running, nor end another purpose's.
     */
    fun stopIfRunningFor(purpose: CapturePurpose, reason: StopReason) {
        val current = _state.value
        if (current is CaptureState.Active && current.purpose == purpose) stop(reason)
    }

    /** `MediaProjection.Callback.onStop`: Android or the user ended the projection. */
    fun onProjectionStopped() {
        stopSession(StopReason.STOPPED_BY_SYSTEM, notifyHost = true)
    }

    /** (Re)starts the idle timer of a running fare-reading projection; every Read fares grab pushes it back. */
    private fun armIdleStop() {
        val scope = idleScope ?: return
        synchronized(lock) {
            idleJob?.cancel()
            idleJob = null
            val running = session ?: return
            val current = _state.value
            if (current !is CaptureState.Active || current.purpose != CapturePurpose.FARE_READING) return
            idleJob = scope.launch {
                delay(idleStopMs)
                withContext(mainDispatcher) {
                    if (synchronized(lock) { session === running }) stop(StopReason.IDLE)
                }
            }
        }
    }

    private fun stopSession(reason: StopReason, notifyHost: Boolean) {
        val ending = synchronized(lock) {
            idleJob?.cancel()
            idleJob = null
            val s = session ?: return
            session = null
            if (_state.value is CaptureState.Active) {
                _state.value = if (pendingConsent != null) CaptureState.AwaitingConsent(pendingPurpose!!) else CaptureState.Idle
            }
            s
        }
        _lastStop.value = reason
        runCatching { ending.stop() }.onFailure { crash?.recordNonFatal(it, CrashSite.CAPTURE) }
        if (notifyHost) runCatching { foregroundHost?.exitMediaProjectionForeground() }.onFailure { crash?.recordNonFatal(it, CrashSite.CAPTURE) }
    }

    companion object {
        /** Spacing of the two frames of one tap (§4.2). */
        const val FRAME_GAP_MS: Long = 400

        /** How long to wait for the first frame after the overlays hid. */
        const val FIRST_FRAME_WAIT_MS: Long = 600

        /** How long to wait for a newer second frame (a static screen produces none; the latest one is current). */
        const val SECOND_FRAME_WAIT_MS: Long = 100

        const val CONSENT_TIMEOUT_MS: Long = 120_000

        /** A fare-reading projection with no Read fares tap for this long has outlived fare reading and stops. */
        const val FARE_IDLE_STOP_MS: Long = 5 * 60_000

        private fun defaultLaunch(context: Context, intent: Intent): Boolean {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            context.startActivity(intent)
            return true
        }
    }
}
