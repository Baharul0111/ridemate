package app.ridemate.demo

import app.ridemate.core.model.Provider

/** The three demo walks of `script.json`. */
enum class DemoTrackChoice(val trackId: String, val keepSearching: Boolean) {
    /** Book all three; Pepper finds the first driver; cancel the others with proof and reminders; pay in Pepper. */
    MAIN("main", keepSearching = false),

    /** "Keep searching" on: a much closer Tamarind driver brings a better-ride offer. */
    KEEP_SEARCHING("keep_searching", keepSearching = true),

    /** Pepper only: the driver cancels and Pepper re-matches with a closer driver. */
    REMATCH("rematch", keepSearching = false),
}

enum class DemoPhase {
    IDLE,

    /** Reading prices and booking in each demo app, in walk order. */
    BOOKING,

    /** Everything is booked; the guardian takes over (driver found, cancel the others, reminders, better ride). */
    WATCHING,

    /** The ride ended and RideMate handed over to the demo app for payment. */
    FINISHED,
    UNAVAILABLE,
}

enum class DemoUnavailable {
    /** The demo isn't wired up in this build yet (no OCR or session host bound). */
    NOT_READY,

    /** `demo_enabled` is off in Remote Config. */
    SWITCHED_OFF,

    /** A live ride session still has bookings to cancel. */
    SESSION_BUSY,

    /** The bundled demo files are missing or broken. */
    ASSETS_BROKEN,
}

enum class DemoStartResult { STARTED, ALREADY_RUNNING, NOT_READY, SWITCHED_OFF, SESSION_BUSY, ASSETS_BROKEN }

/** Why a demo app is in front. */
enum class DemoAppPurpose {
    /** Its fare screen, to read prices and book. */
    BOOK,

    /** RideMate opened it to cancel this extra booking (shows its cancel screen). */
    CANCEL,

    /** RideMate opened it to pay at the end of the ride (no money moves in the demo). */
    PAY,

    /** Just looking at its current screen. */
    VIEW,
}

/** A one-off message on the demo screen; each says what to do next. */
enum class DemoNotice {
    /** "Not now" with keep searching off: nothing else is scripted. */
    NOT_NOW_CHOSEN,

    /** Show me needs Google sign-in and AI reading. */
    SHOW_ME_UNAVAILABLE,

    /** The demo was switched off remotely while running. */
    SWITCHED_OFF_WHILE_RUNNING,
}

/** One fictional provider in the demo. */
data class DemoProviderView(
    val provider: Provider,
    val legId: String,
    /** The provider's current screen key (fare_options → searching → driver_assigned → cancelled …). */
    val screen: String,
    val opened: Boolean = false,
    val booked: Boolean = false,
    val reading: Boolean = false,
    val read: DemoReadResult? = null,
    val cancelTapped: Boolean = false,
    val cancelProven: Boolean = false,
)

/** The demo app in front of the user, drawn from a bundled screen. */
data class DemoAppView(
    val provider: Provider,
    val purpose: DemoAppPurpose,
    val screen: String,
    val assetPath: String,
    /** Show me: the target's box on the screen's 0–1000 grid (model output reaches the UI only as a box, I5). */
    val pointer: List<Int>? = null,
)

/** What the demo has shown so far, for the progress checklist. */
data class DemoProgress(
    val pricesRead: Int = 0,
    val booked: Int = 0,
    val toBook: Int = 0,
    val driverFound: Boolean = false,
    val cancelGuideShown: Boolean = false,
    val cancelsProven: Int = 0,
    val reminders: Int = 0,
    val betterRideOffered: Boolean = false,
    val payOffered: Boolean = false,
    /** The demo app the ride ended in (where the user pays). */
    val payProvider: Provider? = null,
    val paymentHandedOff: Boolean = false,
)

data class DemoUiState(
    val phase: DemoPhase = DemoPhase.IDLE,
    val unavailable: DemoUnavailable? = null,
    val track: DemoTrackChoice? = null,
    val sessionId: String? = null,
    val providers: List<DemoProviderView> = emptyList(),
    val app: DemoAppView? = null,
    val progress: DemoProgress = DemoProgress(),
    val notice: DemoNotice? = null,
) {
    val running: Boolean get() = phase == DemoPhase.BOOKING || phase == DemoPhase.WATCHING || phase == DemoPhase.FINISHED

    fun provider(provider: Provider): DemoProviderView? = providers.firstOrNull { it.provider == provider }

    /** The next demo app to book in, in walk order. */
    val nextToBook: DemoProviderView? get() = providers.firstOrNull { !it.booked }
}
