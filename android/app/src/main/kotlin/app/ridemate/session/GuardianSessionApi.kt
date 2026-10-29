package app.ridemate.session

import android.content.Context
import android.content.Intent
import app.ridemate.core.guardian.CaptureReason
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.OpenPurpose
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.model.Place
import app.ridemate.core.model.Provider
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.state.Protocol
import app.ridemate.core.state.RideEvent
import app.ridemate.core.state.TapId
import app.ridemate.core.watch.PostRemoval
import app.ridemate.core.watch.PostSnapshot
import app.ridemate.notify.ActionTarget
import app.ridemate.notify.GuardianUiState
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** One comparison: the trip, the legs to watch and the user's choices. */
data class SessionSpec(
    val pickup: Place,
    val drop: Place,
    /** Ride apps (or demo providers) plus [Provider.ONDC] when the open network is included. One leg each. */
    val providers: List<Provider>,
    val vehicleClasses: List<VehicleClass>,
    /** Opt-in "Keep searching" (off by default, with the fair-play warning). */
    val keepSearching: Boolean = false,
    val sessionId: String = UUID.randomUUID().toString(),
) {
    val includeOndc: Boolean get() = Provider.ONDC in providers
}

/** What the session service, the UI and onboarding need to know about the guardian (I8 / M7). */
data class GuardianStatus(
    /** False until the guardian state of an earlier process has been restored (or found absent). */
    val restored: Boolean = false,
    /** A start was requested and is being applied. */
    val starting: Boolean = false,
    val sessionId: String? = null,
    val demo: Boolean = false,
    /** A session exists and is not closed. */
    val sessionOpen: Boolean = false,
    /** I8: an extra booking is still non-terminal and not dismissed, or a +60 s follow-up check is pending. */
    val needsForegroundService: Boolean = false,
    /** The user tapped Stop/Done; the session closes as soon as I8 allows. */
    val stopRequested: Boolean = false,
    val bookedLegs: Int = 0,
    val pendingExtras: Int = 0,
    /**
     * The leg whose app RideMate opened last in this session: the app on screen as far as RideMate knows. "Booked
     * here" records it (and the bubble names it); null before any app was opened.
     */
    val openedLegId: String? = null,
    /**
     * While I8 holds: when (on `elapsedRealtime`) the guardian's next reminder or +60 s check is due. The session
     * service keeps the CPU awake for it and sets a while-idle alarm as a backstop.
     */
    val nextDueElapsedMs: Long? = null,
) {
    /** M7: the foreground service runs while a session is open or anything still needs reminding. */
    val shouldRunService: Boolean get() = !restored || starting || needsForegroundService || (sessionOpen && !stopRequested)
}

/**
 * The guardian of the active ride session, for the other areas. Demo, ONDC and messaging feed events in; the UI
 * shell shows [uiState]; the bubble and notifications send taps through [perform] / [onUserAction].
 * Every call returns at once and is applied in order on the guardian's own thread.
 */
interface GuardianSessionApi {
    val uiState: StateFlow<GuardianUiState>
    val status: StateFlow<GuardianStatus>

    /** Starts a live session (from the foreground Compare tap) and the session service. */
    fun startSession(spec: SessionSpec)

    /** Starts a Try-the-demo session: demo providers only, never mixed with live legs or RideWatch. */
    fun startDemoSession(spec: SessionSpec)

    /** The leg id of [provider] in the active session, or null. */
    fun legIdFor(provider: Provider): String?

    /** A RideEvent for a leg (protocol data, scripted demo events, verified screen reads). */
    fun onRideEvent(legId: String, event: RideEvent)

    /** A demo provider's post, fed straight into the RideWatch pipeline (allowed only in a demo session). */
    fun onDemoPost(post: PostSnapshot)

    fun onDemoRemoval(removal: PostRemoval)

    /** ONDC protocol data mirrored from Firestore by OndcClient. */
    fun onProtocolEvent(legId: String, event: Protocol)

    /** A user tap (I1: the action carries its tap id). */
    fun onUserAction(action: UserAction)

    /** A button from a notification, the in-app sheet or the bubble mirror. */
    fun perform(target: ActionTarget)

    /** Stop/Done: closes the session now, or as soon as every extra booking is settled (I8). */
    fun requestStop()
}

/**
 * The commands the bubble, capture and compare areas send to the session (A2's SessionCommands bind to this).
 * Provider-based, because the bubble knows which app is in front, not leg ids.
 */
interface SessionCommands {
    /** The launcher opened [provider] with the route (SELECTED → OPENED). */
    fun appOpened(provider: Provider)

    /** Fares were read from [provider]'s screen (→ QUOTED). */
    fun faresRead(provider: Provider)

    /** "Booked here" on the bubble while [provider] is in front. */
    fun bookedHere(provider: Provider)

    /** The fare the user booked at, so fee notes can say "up to 10% of ₹X". */
    fun recordBookedFare(provider: Provider, fareInr: Int)

    /** "Show me" on the bubble for the leg being cancelled. */
    fun showMe(provider: Provider)

    /** Rung 3's capture half: the user-tapped capture's OCR checked against [provider]'s cancelled keywords. */
    fun captureVerdict(provider: Provider, cancelledKeywordsFound: Boolean, tapId: TapId, frameAtElapsedMs: Long)

    /**
     * A driver ETA/distance read through the VerificationGate from a user-tapped capture (VERIFIED), with the hash of
     * the plate on that frame (same-driver detection; the plate itself never leaves the frame).
     */
    fun verifiedDriverReading(provider: Provider, etaMin: Int?, distanceMeters: Int?, tapId: TapId, frameAtElapsedMs: Long, plateHash: String? = null)

    /** The tap id RideMate recorded for the latest Check screen / Show me on [provider] (I6 pairs frames with it). */
    fun captureTap(provider: Provider): TapId?

    fun done()
    fun stop()

    /**
     * MediaProjection consent was granted: the session service adds the mediaProjection foreground-service type.
     * Returns false when the service is not running yet (call again once it is).
     */
    fun captureStarted(): Boolean

    /** The last fare was read: the service drops the mediaProjection type again. */
    fun captureEnded()
}

/** The floating bubble, built by the bubble area and hosted by the session service. */
interface GuardianBubble {
    /** Android 15 background-start rule: RideMate may open another app itself only while this is visible. */
    val isVisible: Boolean

    fun attach(context: Context)
    fun detach()

    /** Reminder pulse (§4.7). */
    fun pulse()

    /** The guided-cancel instruction card for the current leg (steps from `cancel_cards`, fee note from `fee_rules`). */
    fun showCancelCard(card: GuardianEffect.CancelCard)

    fun clearCancelCard()

    companion object {
        /** No bubble bound (yet): every action stays on the ongoing notification. */
        val NONE: GuardianBubble = object : GuardianBubble {
            override val isVisible: Boolean = false
            override fun attach(context: Context) = Unit
            override fun detach() = Unit
            override fun pulse() = Unit
            override fun showCancelCard(card: GuardianEffect.CancelCard) = Unit
            override fun clearCancelCard() = Unit
        }
    }
}

/** How a ride app is opened (the trip/launch area's AppLauncher binds this; Uber may use its universal link). */
fun interface RideAppOpener {
    /** The intent that opens [provider] for [purpose], or null when it cannot be opened on this phone. */
    fun intentFor(provider: Provider, purpose: OpenPurpose): Intent?
}

/** User-tapped captures (the capture area binds this). */
fun interface CaptureRequester {
    fun request(legId: String, provider: Provider, reason: CaptureReason, tapId: TapId?)
}

/** Executes ONDC book/cancel commands after the user's tap (the ONDC area binds this). */
fun interface OndcCommandSink {
    fun execute(command: GuardianEffect.OndcCommand)

    /** The user kept the ride after seeing the network's cancellation fee (ONDC_KEEP_RIDE), with that tap (I1). */
    fun keepRide(legId: String, tapId: TapId? = null) = Unit
}

/** "Compare again (same trip)": the compare area reopens the trip's apps. */
fun interface CompareAgainHandler {
    fun compareAgain(sessionId: String)
}

/** "Read fares" from the ongoing notification: the capture area starts a fare read for the app in front. */
fun interface FareReadRequester {
    fun readFares()
}

/** Starts the session's foreground service. Returns false when Android refuses a start from the background. */
fun interface ServiceStarter {
    fun ensureRunning(): Boolean
}
