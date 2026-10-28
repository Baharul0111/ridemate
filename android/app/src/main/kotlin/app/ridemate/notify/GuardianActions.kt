package app.ridemate.notify

import app.ridemate.core.guardian.SheetChoice
import app.ridemate.core.guardian.UiAction
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.state.Answer
import app.ridemate.core.state.TapId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a button does, shared by notification actions, the in-app sheets and the bubble mirror. Encoded as JSON in
 * notification PendingIntents (ids and enums only, never text).
 */
@Serializable
sealed interface ActionTarget {
    fun encode(): String = ActionJson.encodeToString(ActionTarget.serializer(), this)

    companion object {
        fun decode(raw: String?): ActionTarget? =
            raw?.let { runCatching { ActionJson.decodeFromString(ActionTarget.serializer(), it) }.getOrNull() }
    }
}

/** Which guardian object a [GuardianTap] answers. */
enum class TapScope { SHEET, PROMPT, LEG, SESSION }

/** A guardian button ([UiAction]) on a sheet, prompt, leg or the session. */
@Serializable
@SerialName("tap")
data class GuardianTap(
    val scope: TapScope,
    /** Sheet id, prompt id or leg id (null for the session). */
    val ref: String?,
    val action: UiAction,
    /** The driver picked on a "Two drivers found" / "Same driver" sheet. */
    val pickedLegId: String? = null,
) : ActionTarget {

    /** The core user action for this tap, or null when the combination is not a guardian answer. */
    fun toUserAction(tap: TapId): UserAction? = when (scope) {
        TapScope.SHEET -> ref?.let { sheetId -> sheetChoice()?.let { UserAction.AnswerSheet(sheetId, it, tap, pickedLegId) } }
        TapScope.PROMPT -> ref?.let { promptId -> promptAnswer()?.let { UserAction.AnswerPrompt(promptId, it, tap) } }
        TapScope.LEG -> ref?.let { legAction(it, tap) }
        TapScope.SESSION -> if (action == UiAction.COMPARE_AGAIN) UserAction.CompareAgain(tap) else null
    }

    private fun sheetChoice(): SheetChoice? = when (action) {
        UiAction.YES_CANCEL_OTHERS -> SheetChoice.YES_CANCEL_OTHERS
        UiAction.KEEP_SEARCHING -> SheetChoice.KEEP_SEARCHING
        UiAction.NOT_NOW -> SheetChoice.NOT_NOW
        UiAction.PICK -> if (pickedLegId != null) SheetChoice.PICK else null
        UiAction.SWITCH -> SheetChoice.SWITCH
        UiAction.KEEP_CURRENT -> SheetChoice.KEEP_CURRENT
        else -> null
    }

    private fun promptAnswer(): Answer? = when (action) {
        UiAction.YES, UiAction.YES_CANCEL_OTHERS -> Answer.YES
        UiAction.NOT_YET -> Answer.NOT_YET
        UiAction.DRIVER_CANCELLED -> Answer.DRIVER_CANCELLED
        UiAction.NO_IT_ENDED -> Answer.NO_IT_ENDED
        UiAction.CHECK_SCREEN -> Answer.CHECK_SCREEN
        else -> null
    }

    private fun legAction(legId: String, tap: TapId): UserAction? = when (action) {
        UiAction.I_CANCELLED_IT -> UserAction.ICancelledIt(legId, tap)
        UiAction.CHECK_SCREEN -> UserAction.CheckScreen(legId, tap)
        UiAction.SHOW_ME -> UserAction.ShowMe(legId, tap)
        UiAction.OPEN_APP -> UserAction.OpenApp(legId, tap)
        UiAction.DISMISS -> UserAction.DismissLeg(legId, tap)
        UiAction.PAY -> UserAction.Pay(legId, tap)
        UiAction.ONDC_CONFIRM_CANCEL -> UserAction.OndcConfirmCancel(legId, tap)
        UiAction.ONDC_KEEP_RIDE -> UserAction.OndcKeepRide(legId, tap)
        UiAction.COMPARE_AGAIN -> UserAction.CompareAgain(tap)
        else -> null
    }
}

/** Session-level buttons mirrored from the bubble (Read fares · Booked here · Done · Stop) and UI housekeeping. */
enum class SessionOpKind { READ_FARES, BOOKED_HERE, DONE, STOP, TOGGLE_KEEP_SEARCHING, DISMISS_NOTICE, OPEN_GUARDIAN }

@Serializable
@SerialName("session")
data class SessionOp(val op: SessionOpKind, val ref: String? = null) : ActionTarget

/** One button: what it does, its visible label, and what a screen reader says. */
data class CardAction(val target: ActionTarget, val label: String, val description: String)

internal val ActionJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    classDiscriminator = "t"
}
