package app.ridemate.dev

import android.app.Activity
import app.ridemate.core.model.Provider
import app.ridemate.core.model.ScreenType

/** Outcome of the Recorder's capture test (architecture §4.19 b). */
sealed interface CaptureTestResult {
    /** One user-tapped capture ran: whether the frame was black (FLAG_SECURE) and whether the bubble showed. */
    data class Done(val blackFrame: Boolean, val bubbleVisible: Boolean) : CaptureTestResult

    /** The owner declined Android's screen-capture prompt. */
    data object Denied : CaptureTestResult

    /** Screen capture is not available in this build (not bound yet) or on this phone. */
    data object Unavailable : CaptureTestResult
}

/**
 * Runs one capture on the owner's tap and reports what it saw. Bound by the integration to A2's CaptureManager;
 * it only captures the owner's own screen after Android's consent prompt and never acts in any app.
 */
fun interface CaptureTester {
    suspend fun runCaptureTest(activity: Activity): CaptureTestResult

    companion object {
        val Unavailable: CaptureTester = CaptureTester { CaptureTestResult.Unavailable }
    }
}

/**
 * A screen captured on the owner's tap and **already masked** by the capture pipeline (OcrAndMask: phone numbers,
 * OTPs, plates, names, status bar). Raw frames never reach the developer screens.
 */
class MaskedCapture(
    val png: ByteArray,
    val width: Int,
    val height: Int,
    /** The ride app in front when captured, if known (pre-fills the label form). */
    val provider: Provider? = null,
    /** The screen type the reader saw, if known (pre-fills the label form; the owner confirms). */
    val screenType: ScreenType? = null,
)

sealed interface GoldenCaptureResult {
    data class Captured(val capture: MaskedCapture) : GoldenCaptureResult

    /** The owner declined Android's screen-capture prompt. */
    data object Denied : GoldenCaptureResult

    /** The app in front blocks capture (FLAG_SECURE): the frame was black, nothing was saved. */
    data object BlackFrame : GoldenCaptureResult

    data object Unavailable : GoldenCaptureResult
}

/** Source of masked golden captures (architecture §4.19 c). Bound by the integration to A2's capture + mask. */
fun interface GoldenCaptureSource {
    suspend fun captureMasked(activity: Activity): GoldenCaptureResult

    companion object {
        val Unavailable: GoldenCaptureSource = GoldenCaptureSource { GoldenCaptureResult.Unavailable }
    }
}
