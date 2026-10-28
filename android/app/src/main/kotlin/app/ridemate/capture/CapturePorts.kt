package app.ridemate.capture

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.StringRes
import app.ridemate.R

/** Why the screen is being captured. Only [FARE_READING] keeps the projection across taps (one consent per comparison). */
enum class CapturePurpose {
    /** "Read fares": one consent per comparison, stopped right after the last fare read. */
    FARE_READING,

    /** "Show me" (cancel pointer): optional, a fresh consent each time ("Android will ask again"). */
    SHOW_ME,

    /** Driver ETA check on an assigned screen: optional, fresh consent. */
    DRIVER_ETA,

    /** Cancel proof (§4.6 rung 3): optional, fresh consent. */
    CANCEL_PROOF,

    /** Developer-mode capture test (§4.19 b): fresh consent. */
    CAPTURE_TEST;

    /** True for the later, optional captures that are labelled "Android will ask again". */
    val asksAgain: Boolean get() = this != FARE_READING

    /** The "Android will ask again" label to show next to an optional capture's button; null for fare reading. */
    @get:StringRes
    val askAgainLabelRes: Int? get() = if (asksAgain) R.string.capture_android_will_ask_again else null
}

/**
 * The session foreground service's side of capture (A3's RideSessionService implements it). Android requires a
 * running foreground service of type `mediaProjection` before a projection may start (API 29+), and on API 34+ that
 * type may only be added after the user granted consent. RideMate keeps the type only between consent and the end
 * of fare reading (§4.2 RideSessionService).
 */
interface CaptureForegroundHost {
    /**
     * Re-posts the session's foreground notification with [ForegroundTypes.sessionTypes]`(projectionActive = true)`.
     * Called on the main thread right after consent; false when the service isn't running or Android refused.
     */
    fun enterMediaProjectionForeground(): Boolean

    /**
     * Back to [ForegroundTypes.sessionTypes]`(projectionActive = false)`; called after the projection stopped. May be
     * called from the capture thread when Android stops the projection, so implementations must be thread-safe.
     */
    fun exitMediaProjectionForeground()
}

/** Foreground service type masks for the session service (manifest: `specialUse|mediaProjection`). */
object ForegroundTypes {
    /**
     * The `startForeground` type for the session service. API 34+: `specialUse`, plus `mediaProjection` while a
     * projection runs. API 29–33 has no `specialUse`, so the mask is `mediaProjection` while projecting, else 0
     * (call the two-argument `startForeground`).
     */
    @SuppressLint("InlinedApi") // SPECIAL_USE is a compile-time constant, only used on API 34+.
    fun sessionTypes(projectionActive: Boolean, sdkInt: Int = Build.VERSION.SDK_INT): Int {
        val projection = if (projectionActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        return if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or projection
        } else {
            projection
        }
    }
}

/**
 * What capture needs from RideMate's own overlays: they must not appear in the frames. The bubble hides itself (and
 * any pointer arrow) before the grab and comes back right after. Implemented by `bubble.BubbleController`.
 */
interface CaptureOverlayControl {
    /** Makes RideMate's overlays invisible and returns once the change has reached the screen. */
    suspend fun hideForCapture()

    /** Shows the overlays hidden by [hideForCapture] again. */
    fun restoreAfterCapture()
}

/** A started screen projection: one VirtualDisplay + ImageReader mirroring the user's own default display. */
interface ProjectionSession {
    /**
     * The current screen as a new in-memory frame. Waits up to [timeoutMs] for a frame produced at or after
     * [notBeforeElapsedMs] (hiding the bubble produces one); if none arrives, the latest frame is current (a virtual
     * display only posts frames when the screen changes). Null when no frame exists at all or the projection ended.
     */
    suspend fun grab(notBeforeElapsedMs: Long, timeoutMs: Long): FrameImage?

    /** Releases the VirtualDisplay and ImageReader and stops the MediaProjection. Idempotent. */
    fun stop()
}

/** The MediaProjection platform seam (production: [MediaProjectionGateway]; tests: a fake). */
interface ProjectionGateway {
    /** The system consent intent (`createScreenCaptureIntent`, default display on API 34+). */
    fun consentIntent(): Intent

    /**
     * Starts the projection from the consent result. [onStop] runs when Android (or the user, from the status bar)
     * stops it. Null when it could not start (consent token already used, foreground service missing).
     */
    fun start(resultCode: Int, data: Intent, onStop: () -> Unit): ProjectionSession?
}

/**
 * Two frames of one "Read fares" tap, or why there are none. Frames must be closed by the receiver. A frame that
 * looks like a FLAG_SECURE capture carries [FrameImage.suspectedSecure]; the reader confirms it with OCR.
 */
sealed interface CaptureOutcome {
    data class Frames(val first: FrameImage, val second: FrameImage) : CaptureOutcome, AutoCloseable {
        override fun close() {
            first.close()
            second.close()
        }
    }

    /** No running projection: consent is needed first (or the user stopped sharing). */
    data object NotActive : CaptureOutcome

    /** No frame could be read (the projection ended mid-grab). */
    data object Failed : CaptureOutcome
}

/** Lets the fare reader capture without knowing about MediaProjection (tests fake it). */
fun interface FareFrameSource {
    /** Hides RideMate's overlays, grabs 2 frames 400 ms apart, shows the overlays again. */
    suspend fun captureTwoFrames(tapAtElapsedMs: Long): CaptureOutcome
}
