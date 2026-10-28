package app.ridemate.demo

import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.model.Provider
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.core.state.TapId
import app.ridemate.core.watch.PostRemoval
import app.ridemate.core.watch.PostSnapshot
import kotlinx.coroutines.flow.Flow

/*
 * What Try the demo needs from the areas built in parallel. Integration binds them with DemoController.bind():
 * - DemoOcr        ← the capture area's ML Kit OcrEngine (Channel B tokens, Latin + Devanagari);
 * - DemoSessionHost ← the session area's GuardianSessionApi (guardian + RideWatch + session service + notify UI).
 */

/** ML Kit Text Recognition v2 over one bundled demo screen (the capture area's OcrEngine). */
fun interface DemoOcr {
    /** OCR tokens of the PNG [image] (pixel boxes in the image's own frame). Throws on failure. */
    suspend fun recognize(image: ByteArray): OcrFrame
}

/**
 * The ride session as the demo drives it. The implementation (session area) owns the one guardian and RideWatch
 * pipeline of the process and confines calls to their dispatcher; every method here may be called from the main thread.
 *
 * Demo isolation (I7, "never mixes with live legs"):
 * - [startDemoSession] starts a guardian session with `demo = true` for demo providers only, and sets RideWatch's
 *   `demoSessionActive` so `demo.*` packages are read only while this session runs; [endDemoSession] clears it.
 * - Demo posts reach RideWatch through [post]/[remove] (straight into the pipeline), never through the
 *   NotificationListenerService allowlist.
 * - [GuardianEffect.OpenApp] and [GuardianEffect.RequestCapture] for demo providers are handled by the demo
 *   ([DemoController.onEffect]); the session area must not launch an intent or start a screen capture for them.
 *   Every other effect (sheets, prompts, reminders, banners, pay offer) is rendered by the session area as for live
 *   legs, with each provider labelled DEMO.
 */
interface DemoSessionHost {
    /**
     * Starts the demo session (keep searching per the track). Returns each provider's leg id, or null when a live
     * session still has bookings to cancel (the demo then asks the user to finish that first).
     */
    fun startDemoSession(sessionId: String, providers: List<Provider>, keepSearching: Boolean): Map<Provider, String>?

    /** A scripted demo post, processed by RideWatch exactly like a ride-app post. */
    fun post(post: PostSnapshot)

    /** A scripted removal of a demo post (with the provider's removal reason). */
    fun remove(removal: PostRemoval)

    /** A tap on a demo control ("Booked here", "Show me"). The host forwards it to the guardian. */
    fun userAction(action: UserAction)

    fun markOpened(legId: String)

    fun markQuoted(legId: String)

    /** The fare the user booked at (a VERIFIED price), for fee notes. */
    fun recordBookedFare(legId: String, fareInr: Int)

    /** Rung 3's capture half for a demo screen (demo frames are exempt from the 2 s tap window, I6). */
    fun captureVerdict(legId: String, cancelledKeywordsFound: Boolean, tapId: TapId)

    /** A driver ETA/distance read through the gate from a demo screen (VERIFIED). */
    fun verifiedDriverReading(legId: String, etaMin: Int?, distanceMeters: Int?, tapId: TapId)

    /** Every tap the guardian receives during the session, from any surface (sheets, bubble, notifications). */
    val userActions: Flow<UserAction>

    /** Every effect the guardian emits during the session. */
    val effects: Flow<GuardianEffect>

    /** Ends the demo session: closes the guardian session (demo bookings are fictional) and clears the demo flag. */
    fun endDemoSession()
}
