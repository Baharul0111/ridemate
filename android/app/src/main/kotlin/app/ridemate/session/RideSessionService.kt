package app.ridemate.session

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.ridemate.RideMateApp
import app.ridemate.crash.CrashSite
import app.ridemate.notify.GuardianCard
import app.ridemate.notify.GuardianUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The ride session's foreground service (§4.2 RideSessionService, M7).
 *
 * - Type `specialUse`, plus `mediaProjection` only between capture consent and the end of fare reading
 *   ([beginCapture] / [endCapture]).
 * - Started from the foreground Compare tap (or after a restore at boot); it keeps the guardian alive, hosts the
 *   bubble, and every second fires the guardian's due reminders and timers and ticks the platform outbox, so
 *   reminders never depend on WorkManager under Doze.
 * - It runs while a session is open or I8 holds ([GuardianStatus.shouldRunService]: an extra booking is still
 *   non-terminal and not dismissed, or a +60 s follow-up is pending) and stops itself otherwise.
 */
class RideSessionService : LifecycleService() {

    private val graph by lazy { RideMateApp.graph(this) }
    private val module get() = graph.guardian
    private var tickJob: Job? = null
    private var capturing = false
    private var stopping = false

    /**
     * onStartCommand() has run, so startForeground() was called. A service started with startForegroundService() must
     * not stop before that, or Android crashes the app ("did not then call Service.startForeground()").
     */
    private var commanded = false

    /** The newest start request: stopping uses it, so a start that arrives while stopping keeps the service. */
    private var lastStartId = 0

    /** The ongoing notification as last posted. */
    private var lastOngoing: GuardianCard? = null

    override fun onCreate() {
        super.onCreate()
        running = this
        graph.sessionActivity.setActive(true)
        module.runtime.start()
        try {
            graph.guardianBindings.bubble.attach(this)
        } catch (e: RuntimeException) {
            graph.crashReporter.recordNonFatal(e, CrashSite.GUARDIAN)
        }
        lifecycleScope.launch {
            combine(module.runtime.status, module.runtime.uiState) { status, ui -> status to ui }
                .collect { (status, ui) -> onGuardian(status, ui) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        stopping = false
        when (intent?.action) {
            ACTION_CAPTURE_BEGIN -> capturing = true
            ACTION_CAPTURE_END -> capturing = false
        }
        // Always promote first, then decide whether to stay (see [commanded]).
        promote()
        commanded = true
        if (!needed(module.runtime.status.value)) {
            stopNow()
        } else {
            startTicking()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = null
        tickJob?.cancel()
        graph.sessionActivity.setActive(false)
        try {
            graph.guardianBindings.bubble.detach()
        } catch (e: RuntimeException) {
            graph.crashReporter.recordNonFatal(e, CrashSite.GUARDIAN)
        }
        super.onDestroy()
    }

    /** Adds or drops the mediaProjection type. Returns false if Android refused it (the service stays specialUse). */
    fun setCapturing(on: Boolean): Boolean {
        capturing = on
        val ok = promote()
        if (!ok && on) {
            capturing = false
            promote()
        }
        if (!on) recheck()
        return ok
    }

    /** M7: a session or I8 needs the service, or a capture (fare reading, or a developer capture hold) runs. */
    private fun needed(status: GuardianStatus): Boolean = status.shouldRunService || capturing || captureHolds.get() > 0

    /** Stops the service when nothing needs it any more (after a capture or a capture hold ended). */
    private fun recheck() {
        if (stopping) return
        lifecycleScope.launch { onGuardian(module.runtime.status.value, module.runtime.uiState.value) }
    }

    private fun promote(): Boolean {
        val card = module.presenter().ongoing(module.runtime.uiState.value)
        lastOngoing = card
        val notification = module.notifier.ongoing(card)
        return try {
            ServiceCompat.startForeground(this, ONGOING_NOTIFICATION_ID, notification, foregroundType(capturing))
            true
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException / SecurityException (no projection consent yet).
            graph.crashReporter.recordNonFatal(e, CrashSite.GUARDIAN)
            false
        }
    }

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = lifecycleScope.launch {
            while (isActive) {
                module.runtime.tick()
                try {
                    graph.outboxDriver.tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    graph.crashReporter.recordNonFatal(e, CrashSite.OUTBOX)
                }
                delay(TICK_MS)
            }
        }
    }

    private fun onGuardian(status: GuardianStatus, ui: GuardianUiState) {
        // Before the first onStartCommand the service is not in the foreground yet; that call decides.
        if (!commanded) return
        if (!needed(status)) {
            stopNow()
            return
        }
        // A stopping service must not re-post its notification: it would outlive the service.
        if (stopping) return
        // Re-post only what changed: Android drops a package's notification updates above its enqueue rate, and the
        // reminders are updates too.
        val card = module.presenter().ongoing(ui)
        if (card == lastOngoing) return
        lastOngoing = card
        module.notifier.updateOngoing(ONGOING_NOTIFICATION_ID, card)
    }

    private fun stopNow() {
        if (stopping) return
        stopping = true
        tickJob?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Only when no newer start arrived meanwhile (that start's onStartCommand decides again).
        stopSelfResult(lastStartId)
    }

    companion object {
        const val ONGOING_NOTIFICATION_ID = 4_001
        const val TICK_MS = 1_000L
        const val ACTION_CAPTURE_BEGIN = "app.ridemate.session.CAPTURE_BEGIN"
        const val ACTION_CAPTURE_END = "app.ridemate.session.CAPTURE_END"

        @Volatile
        private var running: RideSessionService? = null

        /** Captures outside a ride session (developer capture test, golden captures) keep the service up. */
        private val captureHolds = AtomicInteger(0)

        /**
         * Starts the service (from the foreground) and keeps it running until the returned handle is closed, so a
         * capture outside a ride session can add the mediaProjection type after consent.
         */
        fun holdForCapture(context: Context): AutoCloseable {
            captureHolds.incrementAndGet()
            ensureRunning(context)
            var closed = false
            return AutoCloseable {
                if (!closed) {
                    closed = true
                    captureHolds.decrementAndGet()
                    running?.recheck()
                }
            }
        }

        val isRunning: Boolean get() = running != null

        /** The foreground-service types for the current phase (specialUse exists from Android 14). */
        fun foregroundType(capturing: Boolean): Int {
            val projection = if (capturing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or projection
            } else {
                projection
            }
        }

        /** Starts the service. Returns false when Android refuses a foreground-service start from the background. */
        fun ensureRunning(context: Context, action: String? = null): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, RideSessionService::class.java).setAction(action))
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }

        /**
         * After MediaProjection consent: adds the mediaProjection type now when the service runs (call on the main
         * thread), else starts it with that type and returns false (call again once it runs).
         */
        fun beginCapture(context: Context): Boolean {
            val service = running ?: run {
                ensureRunning(context, ACTION_CAPTURE_BEGIN)
                return false
            }
            return service.setCapturing(true)
        }

        /** The last fare was read: back to specialUse only. Nothing to do when the service is not running. */
        fun endCapture() {
            running?.setCapturing(false)
        }
    }
}

/** [CaptureGate] over the running session service. */
class ServiceCaptureGate(private val context: Context) : CaptureGate {
    override fun begin(): Boolean = RideSessionService.beginCapture(context)
    override fun end() = RideSessionService.endCapture()
}
