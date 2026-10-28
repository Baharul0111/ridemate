package app.ridemate.home

import android.content.Context
import app.ridemate.R
import app.ridemate.auth.AuthState
import app.ridemate.config.KillSwitches
import app.ridemate.net.ApiMessages
import app.ridemate.net.ApiResult
import app.ridemate.net.ConfigCheckResponse
import app.ridemate.ui.ChipTone
import app.ridemate.ui.UiFormat

/**
 * The home screen's AI status line (goal 21/23): whether AI reading is on, or why the user types prices instead
 * and when AI reading resumes (IST, from `/v1/config-check`). Only AI reading pauses; the guardian keeps working.
 */
sealed interface AiStatus {
    data object Checking : AiStatus

    /** AI reading works; [readsLeft] is today's fair share left, when the server says. */
    data class On(val readsLeft: Int?) : AiStatus

    /** The owner switched AI reading off (kill switch). */
    data object SwitchedOff : AiStatus

    /** No Google sign-in: manual mode until the user signs in. */
    data object ManualMode : AiStatus

    /** Paused (budget, daily cap, quota, …) until [resumeAtIst] ("13:30", IST). */
    data class PausedUntil(val resumeAtIst: String) : AiStatus

    /** Paused with no known resume time. */
    data object Paused : AiStatus

    /** The server could not be asked; [result] says why (offline, not configured, …). */
    data class Unknown(val result: ApiResult<*>) : AiStatus

    companion object {
        /** Reasons that mean "sign in with Google" rather than "paused". */
        private val SIGN_IN_REASONS = setOf("signed_out", "anonymous")

        /**
         * Pure mapping. [check] is null when no request was made (the kill switch is off or the user is not signed in
         * with Google: `/v1/config-check` would only confirm it).
         */
        fun of(auth: AuthState, switches: KillSwitches, check: ApiResult<ConfigCheckResponse>?): AiStatus = when {
            !switches.aiReaderEnabled -> SwitchedOff
            auth !is AuthState.Google -> ManualMode
            check == null -> Checking
            else -> when (check) {
                is ApiResult.Ok -> fromBody(check.body)
                is ApiResult.AiPaused -> check.resumeAtIst?.takeIf { UiFormat.resumeAtIst(it) != null }?.let(::PausedUntil) ?: Paused
                ApiResult.AuthNeeded, ApiResult.GoogleRequired -> ManualMode
                else -> Unknown(check)
            }
        }

        private fun fromBody(body: ConfigCheckResponse): AiStatus = when {
            body.aiEnabled -> On(body.quota?.readsLeft)
            body.reason == "kill_switch" -> SwitchedOff
            body.reason in SIGN_IN_REASONS -> ManualMode
            UiFormat.resumeAtIst(body.resumeAtIst) != null -> PausedUntil(body.resumeAtIst!!)
            else -> Paused
        }
    }
}

/** Wording and tone of an [AiStatus] (strings_home.xml); every non-"on" text says what to do meanwhile. */
object AiStatusText {
    fun text(context: Context, status: AiStatus): String = when (status) {
        AiStatus.Checking -> context.getString(R.string.home_ai_checking)
        is AiStatus.On -> status.readsLeft?.let { context.resources.getQuantityString(R.plurals.home_ai_on_reads_left, it, it) }
            ?: context.getString(R.string.home_ai_on)
        AiStatus.SwitchedOff -> context.getString(R.string.home_ai_switched_off)
        AiStatus.ManualMode -> context.getString(R.string.home_ai_manual)
        is AiStatus.PausedUntil -> context.getString(R.string.home_ai_resumes_at, UiFormat.resumeAtIst(status.resumeAtIst))
        AiStatus.Paused -> context.getString(R.string.home_ai_paused)
        is AiStatus.Unknown -> ApiMessages.text(context, status.result) ?: context.getString(R.string.home_ai_paused)
    }

    fun tone(status: AiStatus): ChipTone = when (status) {
        is AiStatus.On -> ChipTone.GOOD
        AiStatus.Checking -> ChipTone.NEUTRAL
        AiStatus.ManualMode -> ChipTone.NEUTRAL
        else -> ChipTone.WARNING
    }
}
