package app.ridemate.core.guardian

import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.state.TapId

/**
 * String keys for every piece of guardian UI. I5: wording lives in the app's string resources (English + Hindi);
 * effects carry only these keys, enums, numbers and ids — never model output or free text.
 */
enum class UiKey {
    SHEET_CANCEL_OTHERS, SHEET_TWO_DRIVERS_PICK_ONE, SHEET_SAME_DRIVER_KEEP_ONE, SHEET_BETTER_RIDE, SHEET_SECOND_DRIVER,
    SHEET_WITHDRAWN_DRIVER_CANCELLED, SHEET_WITHDRAWN_SEARCHING_AGAIN, SHEET_WITHDRAWN_UPDATE_PENDING,
    SHEET_WITHDRAWN_PROVIDER_CANCELLED, SHEET_WITHDRAWN_SEARCH_EXPIRED, SHEET_WITHDRAWN_TRIP_UNDERWAY,
    SHEET_WITHDRAWN_NEW_DRIVER, SHEET_WITHDRAWN_CURRENT_DRIVER_CLOSE, SHEET_REPLACED, SHEET_CLOSED,

    PROMPT_MAYBE_BOOKED, PROMPT_MAYBE_ASSIGNED, PROMPT_MAYBE_CHANGED, PROMPT_MAYBE_NEW_DRIVER, PROMPT_CHECK_SCREEN,
    PROMPT_WITHDRAWN_SEARCHING_AGAIN, PROMPT_WITHDRAWN_DRIVER_ASSIGNED, PROMPT_WITHDRAWN_DRIVER_ARRIVING,
    PROMPT_WITHDRAWN_DRIVER_CANCELLED, PROMPT_WITHDRAWN_SEARCH_EXPIRED, PROMPT_WITHDRAWN_PROVIDER_CANCELLED,
    PROMPT_WITHDRAWN_RIDE_ENDED, PROMPT_WITHDRAWN_TRIP_STARTED, PROMPT_WITHDRAWN_SAME_DRIVER_STILL_COMING,
    PROMPT_WITHDRAWN_CANCELLING, PROMPT_WITHDRAWN_BOOKING_SEEN, PROMPT_ANSWERED,

    OPEN_APP_TO_CANCEL, OPEN_APP_TO_PAY, OPEN_APP_TO_VIEW, CANCEL_CARD,
    CAPTURE_CHECK_SCREEN, CAPTURE_CANCEL_PROOF, CAPTURE_SHOW_ME,
    REMINDER_CANCEL_PENDING,
    ALERT_KEPT_LEG_LOST, ALERT_STILL_LIVE, ALERT_CHECK_SCREEN,

    /** The kept ride's driver cancelled and "Is a new driver coming?" went unanswered: the ride may be gone. */
    ALERT_KEPT_LEG_MAY_BE_LOST,
    BANNER_YOUR_RIDE, BANNER_FINDING_ANOTHER_CAPTAIN, BANNER_ENDED_BY_APP, BANNER_CANCEL_CONFIRMED,
    BANNER_USER_SAYS_CANCELLED, BANNER_SAME_DRIVER, BANNER_LEG_DISMISSED, BANNER_ALL_CANCELS_DONE,
    PAY_HANDOFF_OFFER,

    /** An ONDC ride ended: there is no app to pay in, so the driver is paid as agreed (RideMate never handles money). */
    BANNER_ONDC_PAY_DRIVER,
    ONDC_BOOK, ONDC_SOFT_CANCEL, ONDC_CONFIRM_CANCEL, ONDC_CANCEL_FEE,
    FOREGROUND_SERVICE_START, FOREGROUND_SERVICE_STOP,
    COMPARE_AGAIN,
    ANSWER_REJECTED_STALE,
}

/** Buttons. The app maps each to a labelled, 48 dp, screen-reader-described control. */
enum class UiAction {
    YES_CANCEL_OTHERS, KEEP_SEARCHING, NOT_NOW, PICK, SWITCH, KEEP_CURRENT,
    YES, NOT_YET, DRIVER_CANCELLED, NO_IT_ENDED, CHECK_SCREEN,

    /** "Did you book on <App>?": [Yes, booked] [No]. */
    YES_BOOKED, NOT_BOOKED,
    I_CANCELLED_IT, SHOW_ME, OPEN_APP, DISMISS, COMPARE_AGAIN, PAY, ONDC_CONFIRM_CANCEL, ONDC_KEEP_RIDE,
}

/**
 * A driver as shown to the user. [etaMin]/[distanceMeters] are non-null only with an admissible source
 * (VERIFIED, TEMPLATE per D8, NETWORK protocol data or USER); otherwise the UI says "see in <app>".
 */
data class DriverCard(
    val legId: String,
    val provider: Provider,
    val generation: Int,
    val etaMin: Int?,
    val etaSource: EtaSource,
    val distanceMeters: Int?,
    val distanceSource: EtaSource,
)

data class LegFee(val legId: String, val provider: Provider, val fee: FeeNote)

data class LegRef(val legId: String, val provider: Provider, val state: RideState)

enum class OpenPurpose { CANCEL, PAY, VIEW }

enum class OndcCommandKind { BOOK, SOFT_CANCEL, CONFIRM_CANCEL }

enum class CaptureReason { CHECK_SCREEN, CANCEL_PROOF, SHOW_ME }

sealed interface GuardianEffect {
    val key: UiKey

    /** "Cancel the others?", "Two drivers found. Pick one", "Same driver on both. Keep one." */
    data class DriverSheet(
        val sheetId: String,
        override val key: UiKey,
        val drivers: List<DriverCard>,
        val others: List<LegFee>,
        val actions: List<UiAction>,
    ) : GuardianEffect

    /** §4.8, bound to both legs' generations. */
    data class BetterRideSheet(
        val sheetId: String,
        override val key: UiKey,
        val current: DriverCard,
        val candidate: DriverCard,
        val currentFee: FeeNote,
        val candidateFee: FeeNote,
        val actions: List<UiAction>,
    ) : GuardianEffect

    data class SheetWithdrawn(val sheetId: String, override val key: UiKey) : GuardianEffect

    data class MaybePrompt(
        val promptId: String,
        val legId: String,
        val provider: Provider,
        val generation: Int,
        override val key: UiKey,
        val actions: List<UiAction>,
        val repeat: Int,
    ) : GuardianEffect

    data class PromptWithdrawn(val promptId: String, val legId: String, override val key: UiKey) : GuardianEffect

    data class CheckScreenPrompt(val legId: String, val provider: Provider) : GuardianEffect {
        override val key: UiKey get() = UiKey.PROMPT_CHECK_SCREEN
        val actions: List<UiAction> get() = listOf(UiAction.CHECK_SCREEN, UiAction.I_CANCELLED_IT, UiAction.OPEN_APP)
    }

    data class OpenApp(val legId: String, val provider: Provider, val purpose: OpenPurpose, val tapId: TapId?) : GuardianEffect {
        override val key: UiKey
            get() = when (purpose) {
                OpenPurpose.CANCEL -> UiKey.OPEN_APP_TO_CANCEL
                OpenPurpose.PAY -> UiKey.OPEN_APP_TO_PAY
                OpenPurpose.VIEW -> UiKey.OPEN_APP_TO_VIEW
            }
    }

    /** The bubble's instruction card: steps come from `cancel_cards` for [provider]; the fee note from `fee_rules`. */
    data class CancelCard(
        val legId: String,
        val provider: Provider,
        val providerState: RideState,
        val fee: FeeNote,
        val position: Int,
        val total: Int,
        val actions: List<UiAction>,
    ) : GuardianEffect {
        override val key: UiKey get() = UiKey.CANCEL_CARD
    }

    data class RequestCapture(val legId: String, val provider: Provider, val reason: CaptureReason) : GuardianEffect {
        override val key: UiKey
            get() = when (reason) {
                CaptureReason.CHECK_SCREEN -> UiKey.CAPTURE_CHECK_SCREEN
                CaptureReason.CANCEL_PROOF -> UiKey.CAPTURE_CANCEL_PROOF
                CaptureReason.SHOW_ME -> UiKey.CAPTURE_SHOW_ME
            }
    }

    /** Heads-up notification + bubble pulse (§4.7). */
    data class Reminder(val attempt: Int, val legs: List<LegRef>, val headsUp: Boolean, val bubblePulse: Boolean) : GuardianEffect {
        override val key: UiKey get() = UiKey.REMINDER_CANCEL_PENDING
        val actions: List<UiAction> get() = listOf(UiAction.OPEN_APP, UiAction.I_CANCELLED_IT, UiAction.DISMISS)
    }

    data class LoudAlert(val legId: String, val provider: Provider, override val key: UiKey, val actions: List<UiAction>) : GuardianEffect

    /** I10: [state] is always ASSIGNED or ARRIVING for BANNER_YOUR_RIDE. */
    data class RideBanner(val driver: DriverCard, val state: RideState) : GuardianEffect {
        override val key: UiKey get() = UiKey.BANNER_YOUR_RIDE
    }

    data class Banner(val legId: String?, val provider: Provider?, override val key: UiKey) : GuardianEffect

    data class SameDriverNotice(val legIds: List<String>) : GuardianEffect {
        override val key: UiKey get() = UiKey.BANNER_SAME_DRIVER
    }

    data class PayHandoffOffer(val legId: String, val provider: Provider) : GuardianEffect {
        override val key: UiKey get() = UiKey.PAY_HANDOFF_OFFER
        val actions: List<UiAction> get() = listOf(UiAction.PAY)
    }

    data class OndcCommand(val legId: String, val command: OndcCommandKind, val tapId: TapId, val itemId: String? = null) : GuardianEffect {
        override val key: UiKey
            get() = when (command) {
                OndcCommandKind.BOOK -> UiKey.ONDC_BOOK
                OndcCommandKind.SOFT_CANCEL -> UiKey.ONDC_SOFT_CANCEL
                OndcCommandKind.CONFIRM_CANCEL -> UiKey.ONDC_CONFIRM_CANCEL
            }
    }

    /** ONDC soft-cancel result: the fee is protocol data, shown before the user confirms. */
    data class OndcCancelFee(val legId: String, val feeInr: Int) : GuardianEffect {
        override val key: UiKey get() = UiKey.ONDC_CANCEL_FEE
        val actions: List<UiAction> get() = listOf(UiAction.ONDC_CONFIRM_CANCEL, UiAction.ONDC_KEEP_RIDE)
    }

    /** I8 / M7: the foreground service runs while any extra leg still needs reminding. */
    data class ForegroundService(val required: Boolean) : GuardianEffect {
        override val key: UiKey get() = if (required) UiKey.FOREGROUND_SERVICE_START else UiKey.FOREGROUND_SERVICE_STOP
    }

    data class CompareAgain(val sessionId: String) : GuardianEffect {
        override val key: UiKey get() = UiKey.COMPARE_AGAIN
    }

    data class AnswerRejected(val tapId: TapId) : GuardianEffect {
        override val key: UiKey get() = UiKey.ANSWER_REJECTED_STALE
    }
}

data class TimedEffect(val atElapsedMs: Long, val effect: GuardianEffect)
