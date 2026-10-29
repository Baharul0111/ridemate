package app.ridemate.core.guardian

import app.ridemate.core.state.Answer
import app.ridemate.core.state.TapId

enum class SheetChoice { YES_CANCEL_OTHERS, KEEP_SEARCHING, NOT_NOW, PICK, SWITCH, KEEP_CURRENT }

/** A user tap. Every action carries its tap id (I1); the guardian records the tap before acting on it. */
sealed interface UserAction {
    val tapId: TapId

    data class BookedHere(val legId: String, override val tapId: TapId) : UserAction
    /**
     * [button] is the button the user tapped, when known: on MAYBE_ASSIGNED only [UiAction.YES_CANCEL_OTHERS] starts the
     * cancel chain, so a plain "Yes" tapped on an older copy of the prompt never cancels anything.
     */
    data class AnswerPrompt(
        val promptId: String,
        val answer: Answer,
        override val tapId: TapId,
        val button: UiAction? = null,
    ) : UserAction

    /** [pickedLegId] is required for [SheetChoice.PICK]. */
    data class AnswerSheet(
        val sheetId: String,
        val choice: SheetChoice,
        override val tapId: TapId,
        val pickedLegId: String? = null,
    ) : UserAction

    data class ICancelledIt(val legId: String, override val tapId: TapId) : UserAction
    data class CheckScreen(val legId: String, override val tapId: TapId) : UserAction
    data class ShowMe(val legId: String, override val tapId: TapId) : UserAction
    data class OpenApp(val legId: String, override val tapId: TapId) : UserAction

    /** Stop reminding about this leg (logged). */
    data class DismissLeg(val legId: String, override val tapId: TapId) : UserAction
    data class CompareAgain(override val tapId: TapId) : UserAction
    data class Pay(val legId: String, override val tapId: TapId) : UserAction
    data class OndcBook(val legId: String, val itemId: String, override val tapId: TapId) : UserAction
    data class OndcCancel(val legId: String, override val tapId: TapId) : UserAction
    data class OndcConfirmCancel(val legId: String, override val tapId: TapId) : UserAction
    data class OndcKeepRide(val legId: String, override val tapId: TapId) : UserAction
}
