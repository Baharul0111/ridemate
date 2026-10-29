package app.ridemate.notify

import android.content.Context
import androidx.annotation.StringRes
import app.ridemate.R
import app.ridemate.core.format.DistanceText
import app.ridemate.core.format.IndianFormat
import app.ridemate.core.format.IstTime
import app.ridemate.core.guardian.CaptureReason
import app.ridemate.core.guardian.FeeNote
import app.ridemate.core.guardian.FeeNoteKind
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.OpenPurpose
import app.ridemate.core.guardian.UiAction
import app.ridemate.core.guardian.UiKey
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState

/**
 * Every piece of guardian wording, from string resources (English + Hindi). I5: model output never reaches these
 * strings; they are filled only with provider names, numbers from admissible sources and formatted times/amounts.
 */
class GuardianText(private val context: Context) {

    private fun s(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    /** Rapido calls its drivers "captains" (§4.4 "Rapido is finding another captain"). */
    private fun captain(provider: Provider?): Boolean = provider == Provider.RAPIDO

    fun provider(provider: Provider): String = s(
        when (provider) {
            Provider.UBER -> R.string.guardian_provider_uber
            Provider.OLA -> R.string.guardian_provider_ola
            Provider.RAPIDO -> R.string.guardian_provider_rapido
            Provider.ONDC -> R.string.guardian_provider_ondc
            Provider.DEMO_KESTREL -> R.string.guardian_provider_demo_kestrel
            Provider.DEMO_PEPPER -> R.string.guardian_provider_demo_pepper
            Provider.DEMO_TAMARIND -> R.string.guardian_provider_demo_tamarind
        },
    )

    /** "Ola", "Ola and Uber", "Ola, Uber and Rapido". */
    fun providers(list: List<Provider>): String {
        val names = list.distinct().map(::provider)
        return when (names.size) {
            0 -> ""
            1 -> names[0]
            else -> s(R.string.guardian_list_last, names.dropLast(1).joinToString(s(R.string.guardian_list_separator)), names.last())
        }
    }

    fun rupees(amount: Int): String = IndianFormat.rupees(amount.toLong())

    /** IST, 12-hour (goal 26). */
    fun time(wallMs: Long): String = IstTime.resumeTime(wallMs)

    // ------------------------------------------------------------------ drivers and fees

    /** "4 min away (from Uber notification) · 1.2 km away", or "ETA and distance: see in Uber". */
    fun driverDetails(driver: DriverUi): String {
        val app = provider(driver.provider)
        val eta = driver.etaMin?.let { s(R.string.guardian_eta_min, it) + " (" + source(driver.etaSource, driver.provider) + ")" }
        val distance = driver.distanceMeters?.let { s(R.string.guardian_distance, DistanceText.format(it)) + " (" + source(driver.distanceSource, driver.provider) + ")" }
        return when {
            eta != null && distance != null -> "$eta · $distance"
            eta != null -> eta + " · " + s(R.string.guardian_distance_see_in_app, app)
            distance != null -> s(R.string.guardian_eta_see_in_app, app) + " · " + distance
            else -> s(R.string.guardian_see_in_app, app)
        }
    }

    /** "Uber: 4 min away (…) · 1.2 km away (…)". */
    fun driverLine(driver: DriverUi): String = s(R.string.guardian_line, provider(driver.provider), driverDetails(driver))

    /** D8 / I2 label of where a displayed value came from. */
    fun source(source: EtaSource, provider: Provider): String = when (source) {
        EtaSource.TEMPLATE -> s(R.string.guardian_source_template, provider(provider))
        EtaSource.VERIFIED -> s(R.string.guardian_source_verified)
        EtaSource.NETWORK -> s(R.string.guardian_source_network)
        EtaSource.USER -> s(R.string.guardian_source_user)
        EtaSource.NONE -> s(R.string.guardian_see_in_app, provider(provider))
    }

    fun fee(note: FeeNote): String = when (note.kind) {
        FeeNoteKind.FREE_WHILE_SEARCHING -> s(R.string.guardian_fee_free_searching)
        FeeNoteKind.FREE_WINDOW -> {
            val seconds = note.freeForSeconds ?: 0
            if (seconds > 90) s(R.string.guardian_fee_free_window_min, (seconds + 59) / 60) else s(R.string.guardian_fee_free_window_s, seconds)
        }
        FeeNoteKind.FREE_DRIVER_LATE -> s(R.string.guardian_fee_free_driver_late)
        FeeNoteKind.MAY_APPLY -> note.upToInr?.let { s(R.string.guardian_fee_may_apply, rupees(it)) } ?: s(R.string.guardian_fee_may_apply_unknown)
        FeeNoteKind.SHOWN_BEFORE_CONFIRM -> s(R.string.guardian_fee_shown_before_confirm)
        FeeNoteKind.NETWORK_FEE -> s(R.string.guardian_fee_network, rupees(note.upToInr ?: 0))
    }

    fun feeLine(provider: Provider, note: FeeNote): String = s(R.string.guardian_line, provider(provider), fee(note))

    // ------------------------------------------------------------------ legs

    fun state(state: RideState, provider: Provider): String {
        val app = provider(provider)
        val c = captain(provider)
        return when (state) {
            RideState.SELECTED, RideState.OPENED, RideState.QUOTED -> s(R.string.guardian_state_not_booked)
            RideState.BOOK_REQUESTED -> s(R.string.guardian_state_book_requested, app)
            RideState.SEARCHING -> s(if (c) R.string.guardian_state_searching_captain else R.string.guardian_state_searching)
            RideState.SEARCHING_NET, RideState.ON_SELECT, RideState.INIT -> s(R.string.guardian_state_network_preparing)
            RideState.MAYBE_BOOKED, RideState.MAYBE_ASSIGNED, RideState.MAYBE_CHANGED, RideState.MAYBE_NEW_DRIVER ->
                s(R.string.guardian_state_question)
            RideState.ASSIGNED -> s(if (c) R.string.guardian_state_assigned_captain else R.string.guardian_state_assigned)
            RideState.ARRIVING -> s(if (c) R.string.guardian_state_arriving_captain else R.string.guardian_state_arriving)
            RideState.DRIVER_CANCELLED ->
                s(if (c) R.string.guardian_state_driver_cancelled_captain else R.string.guardian_state_driver_cancelled, app)
            RideState.ON_TRIP -> s(R.string.guardian_state_on_trip)
            RideState.ENDED -> s(R.string.guardian_state_ended, app)
            RideState.PAY_HANDOFF -> s(R.string.guardian_state_pay_handoff, app)
            RideState.SEARCH_EXPIRED -> s(R.string.guardian_state_search_expired, app)
            RideState.PROVIDER_CANCELLED -> s(R.string.guardian_state_provider_cancelled, app)
            RideState.CANCEL_REQUESTED -> s(R.string.guardian_state_cancel_requested)
            RideState.CANCEL_GUIDED -> s(R.string.guardian_state_cancel_guided, app)
            RideState.USER_SAYS_CANCELLED -> s(R.string.guardian_state_user_says_cancelled)
            RideState.CANCEL_UNCONFIRMED -> s(R.string.guardian_state_cancel_unconfirmed, app)
            RideState.CANCEL_CONFIRMED -> s(R.string.guardian_state_cancel_confirmed)
            RideState.CONFIRMED -> s(R.string.guardian_state_network_confirmed)
            RideState.SOFT_CANCEL -> s(R.string.guardian_state_soft_cancel)
            RideState.CONFIRM_CANCEL -> s(R.string.guardian_state_confirm_cancel)
        }
    }

    fun role(role: LegRole, dismissed: Boolean): String? = when {
        dismissed -> s(R.string.guardian_role_dismissed)
        role == LegRole.KEPT -> s(R.string.guardian_role_kept)
        role == LegRole.EXTRA -> s(R.string.guardian_role_extra)
        role == LegRole.CANDIDATE -> s(R.string.guardian_role_candidate)
        else -> null
    }

    fun evidence(rung: EvidenceRung, provider: Provider): String = when (rung) {
        EvidenceRung.ONDC_ON_CANCEL -> s(R.string.guardian_evidence_ondc)
        EvidenceRung.TEMPLATE -> s(R.string.guardian_evidence_template, provider(provider))
        EvidenceRung.REMOVAL_PLUS_CAPTURE -> s(R.string.guardian_evidence_removal_capture, provider(provider))
        EvidenceRung.USER -> s(R.string.guardian_evidence_user)
    }

    fun generation(generation: Int): String = s(R.string.guardian_screen_generation, generation)

    // ------------------------------------------------------------------ keys

    /** The headline for [key]. [providers] fill the app name(s); Rapido gets "captain" wording. */
    fun title(key: UiKey, providers: List<Provider> = emptyList()): String {
        val first = providers.firstOrNull()
        val app = first?.let(::provider) ?: ""
        val apps = providers(providers)
        val c = captain(first)
        return when (key) {
            UiKey.SHEET_CANCEL_OTHERS -> s(R.string.guardian_sheet_cancel_others)
            UiKey.SHEET_TWO_DRIVERS_PICK_ONE -> s(R.string.guardian_sheet_two_drivers)
            UiKey.SHEET_SAME_DRIVER_KEEP_ONE -> s(R.string.guardian_sheet_same_driver)
            UiKey.SHEET_BETTER_RIDE -> s(R.string.guardian_sheet_better_ride)
            UiKey.SHEET_SECOND_DRIVER -> s(R.string.guardian_sheet_second_driver)
            UiKey.SHEET_WITHDRAWN_DRIVER_CANCELLED ->
                s(if (c) R.string.guardian_sheet_withdrawn_driver_cancelled_captain else R.string.guardian_sheet_withdrawn_driver_cancelled, app)
            UiKey.SHEET_WITHDRAWN_SEARCHING_AGAIN -> s(R.string.guardian_sheet_withdrawn_searching_again, app)
            UiKey.SHEET_WITHDRAWN_UPDATE_PENDING -> s(R.string.guardian_sheet_withdrawn_update_pending, app)
            UiKey.SHEET_WITHDRAWN_PROVIDER_CANCELLED -> s(R.string.guardian_sheet_withdrawn_provider_cancelled, app)
            UiKey.SHEET_WITHDRAWN_SEARCH_EXPIRED -> s(R.string.guardian_sheet_withdrawn_search_expired, app)
            UiKey.SHEET_WITHDRAWN_TRIP_UNDERWAY -> s(R.string.guardian_sheet_withdrawn_trip_underway)
            UiKey.SHEET_WITHDRAWN_NEW_DRIVER -> s(R.string.guardian_sheet_withdrawn_new_driver, app)
            UiKey.SHEET_WITHDRAWN_CURRENT_DRIVER_CLOSE -> s(R.string.guardian_sheet_withdrawn_current_driver_close)
            UiKey.SHEET_REPLACED -> s(R.string.guardian_sheet_replaced)
            UiKey.SHEET_CLOSED -> s(R.string.guardian_sheet_closed)
            UiKey.PROMPT_MAYBE_BOOKED -> s(R.string.guardian_prompt_maybe_booked, app)
            UiKey.PROMPT_MAYBE_ASSIGNED -> s(R.string.guardian_prompt_maybe_assigned, app)
            UiKey.PROMPT_MAYBE_CHANGED ->
                s(if (c) R.string.guardian_prompt_maybe_changed_captain else R.string.guardian_prompt_maybe_changed, app)
            UiKey.PROMPT_MAYBE_NEW_DRIVER ->
                s(if (c) R.string.guardian_prompt_maybe_new_driver_captain else R.string.guardian_prompt_maybe_new_driver, app)
            UiKey.PROMPT_CHECK_SCREEN -> s(R.string.guardian_prompt_check_screen, app)
            UiKey.PROMPT_WITHDRAWN_SEARCHING_AGAIN -> s(R.string.guardian_prompt_withdrawn_searching_again, app)
            UiKey.PROMPT_WITHDRAWN_DRIVER_ASSIGNED -> s(R.string.guardian_prompt_withdrawn_driver_assigned, app)
            UiKey.PROMPT_WITHDRAWN_DRIVER_ARRIVING -> s(R.string.guardian_prompt_withdrawn_driver_arriving, app)
            UiKey.PROMPT_WITHDRAWN_DRIVER_CANCELLED -> s(R.string.guardian_prompt_withdrawn_driver_cancelled, app)
            UiKey.PROMPT_WITHDRAWN_SEARCH_EXPIRED -> s(R.string.guardian_prompt_withdrawn_search_expired, app)
            UiKey.PROMPT_WITHDRAWN_PROVIDER_CANCELLED -> s(R.string.guardian_prompt_withdrawn_provider_cancelled, app)
            UiKey.PROMPT_WITHDRAWN_RIDE_ENDED -> s(R.string.guardian_prompt_withdrawn_ride_ended, app)
            UiKey.PROMPT_WITHDRAWN_TRIP_STARTED -> s(R.string.guardian_prompt_withdrawn_trip_started, app)
            UiKey.PROMPT_WITHDRAWN_SAME_DRIVER_STILL_COMING -> s(R.string.guardian_prompt_withdrawn_same_driver, app)
            UiKey.PROMPT_WITHDRAWN_CANCELLING -> s(R.string.guardian_prompt_withdrawn_cancelling, app)
            UiKey.PROMPT_WITHDRAWN_BOOKING_SEEN -> s(R.string.guardian_prompt_withdrawn_booking_seen, app)
            UiKey.PROMPT_ANSWERED -> s(R.string.guardian_prompt_answered)
            UiKey.OPEN_APP_TO_CANCEL -> s(R.string.guardian_open_cancel, app)
            UiKey.OPEN_APP_TO_PAY -> s(R.string.guardian_open_pay, app)
            UiKey.OPEN_APP_TO_VIEW -> s(R.string.guardian_open_view, app)
            UiKey.CANCEL_CARD -> s(R.string.guardian_cancel_card_short, app)
            UiKey.CAPTURE_CHECK_SCREEN -> s(R.string.guardian_capture_check_screen, app)
            UiKey.CAPTURE_CANCEL_PROOF -> s(R.string.guardian_capture_cancel_proof, app)
            UiKey.CAPTURE_SHOW_ME -> s(R.string.guardian_capture_show_me)
            UiKey.REMINDER_CANCEL_PENDING -> s(R.string.guardian_reminder_title, apps)
            UiKey.ALERT_KEPT_LEG_LOST ->
                s(if (c) R.string.guardian_alert_kept_leg_lost_captain else R.string.guardian_alert_kept_leg_lost, app)
            UiKey.ALERT_STILL_LIVE -> s(R.string.guardian_alert_still_live, app)
            UiKey.ALERT_CHECK_SCREEN -> s(R.string.guardian_alert_check_screen, app)
            UiKey.ALERT_KEPT_LEG_MAY_BE_LOST -> s(R.string.guardian_alert_kept_leg_may_be_lost, app)
            UiKey.BANNER_YOUR_RIDE -> s(R.string.guardian_banner_your_ride, app)
            UiKey.BANNER_FINDING_ANOTHER_CAPTAIN ->
                s(if (c) R.string.guardian_banner_finding_another_captain else R.string.guardian_banner_finding_another, app)
            UiKey.BANNER_ENDED_BY_APP -> s(R.string.guardian_banner_ended_by_app, app)
            UiKey.BANNER_CANCEL_CONFIRMED -> s(R.string.guardian_banner_cancel_confirmed, app)
            UiKey.BANNER_USER_SAYS_CANCELLED -> s(R.string.guardian_banner_user_says_cancelled, app)
            UiKey.BANNER_SAME_DRIVER -> s(R.string.guardian_banner_same_driver)
            UiKey.BANNER_LEG_DISMISSED -> s(R.string.guardian_banner_leg_dismissed, app)
            UiKey.BANNER_ALL_CANCELS_DONE -> s(R.string.guardian_banner_all_cancels_done)
            UiKey.PAY_HANDOFF_OFFER -> s(R.string.guardian_pay_offer, app)
            UiKey.BANNER_ONDC_PAY_DRIVER -> s(R.string.guardian_banner_ondc_pay_driver)
            UiKey.ONDC_BOOK -> s(R.string.guardian_ondc_book)
            UiKey.ONDC_SOFT_CANCEL -> s(R.string.guardian_ondc_soft_cancel)
            UiKey.ONDC_CONFIRM_CANCEL -> s(R.string.guardian_ondc_confirm_cancel)
            UiKey.ONDC_CANCEL_FEE -> s(R.string.guardian_ondc_fee_title)
            UiKey.FOREGROUND_SERVICE_START -> s(R.string.guardian_fgs_start)
            UiKey.FOREGROUND_SERVICE_STOP -> s(R.string.guardian_fgs_stop)
            UiKey.COMPARE_AGAIN -> s(R.string.guardian_compare_again)
            UiKey.ANSWER_REJECTED_STALE -> s(R.string.guardian_answer_rejected)
        }
    }

    // ------------------------------------------------------------------ buttons

    /** The visible label of [action]. [provider] names the app for "Open <App>" / "Pick <App>" / "Open <App> to pay". */
    fun action(action: UiAction, provider: Provider?): String {
        val app = provider?.let(::provider) ?: ""
        return when (action) {
            UiAction.YES_CANCEL_OTHERS -> s(R.string.guardian_action_yes_cancel_others)
            UiAction.KEEP_SEARCHING -> s(R.string.guardian_action_keep_searching)
            UiAction.NOT_NOW -> s(R.string.guardian_action_not_now)
            UiAction.PICK -> s(R.string.guardian_action_pick, app)
            UiAction.SWITCH -> s(R.string.guardian_action_switch)
            UiAction.KEEP_CURRENT -> s(R.string.guardian_action_keep_current)
            UiAction.YES -> s(R.string.guardian_action_yes)
            UiAction.NOT_YET -> s(R.string.guardian_action_not_yet)
            UiAction.YES_BOOKED -> s(R.string.guardian_action_yes_booked)
            UiAction.NOT_BOOKED -> s(R.string.guardian_action_not_booked)
            // §4.5.6 wording: "[Driver cancelled]" for every app, Rapido included.
            UiAction.DRIVER_CANCELLED -> s(R.string.guardian_action_driver_cancelled)
            UiAction.NO_IT_ENDED -> s(R.string.guardian_action_no_it_ended)
            UiAction.CHECK_SCREEN -> s(R.string.guardian_action_check_screen)
            UiAction.I_CANCELLED_IT -> s(R.string.guardian_action_i_cancelled_it)
            UiAction.SHOW_ME -> s(R.string.guardian_action_show_me)
            UiAction.OPEN_APP -> s(R.string.guardian_action_open_app, app)
            UiAction.DISMISS -> s(R.string.guardian_action_dismiss)
            UiAction.COMPARE_AGAIN -> s(R.string.guardian_action_compare_again)
            UiAction.PAY -> s(R.string.guardian_action_pay, app)
            UiAction.ONDC_CONFIRM_CANCEL -> s(R.string.guardian_action_ondc_confirm_cancel)
            UiAction.ONDC_KEEP_RIDE -> s(R.string.guardian_action_ondc_keep_ride)
        }
    }

    /** What a screen reader says for [action]: the label plus the app it acts on. */
    fun actionDescription(action: UiAction, provider: Provider?): String = when {
        action == UiAction.DISMISS && provider != null -> s(R.string.guardian_action_dismiss_desc, provider(provider))
        provider != null && action !in SELF_NAMING -> s(R.string.guardian_action_desc_with_app, action(action, provider), provider(provider))
        else -> action(action, provider)
    }

    fun sessionAction(op: SessionOpKind, keepSearchingOn: Boolean = false): String = when (op) {
        SessionOpKind.READ_FARES -> s(R.string.guardian_action_read_fares)
        SessionOpKind.BOOKED_HERE -> s(R.string.guardian_action_booked_here)
        SessionOpKind.DONE -> s(R.string.guardian_action_done)
        SessionOpKind.STOP -> s(R.string.guardian_action_stop)
        SessionOpKind.TOGGLE_KEEP_SEARCHING ->
            s(if (keepSearchingOn) R.string.guardian_action_keep_searching_on else R.string.guardian_action_keep_searching_off)
        SessionOpKind.DISMISS_NOTICE -> s(R.string.guardian_screen_dismiss_notice)
        SessionOpKind.OPEN_GUARDIAN -> s(R.string.guardian_screen_title)
    }

    fun openBody(purpose: OpenPurpose, provider: Provider, hasApp: Boolean): String {
        val app = provider(provider)
        if (!hasApp) return s(R.string.guardian_open_no_app, app)
        return when (purpose) {
            OpenPurpose.CANCEL -> s(R.string.guardian_open_cancel_body, app)
            OpenPurpose.PAY -> s(R.string.guardian_open_pay_body, app)
            OpenPurpose.VIEW -> s(R.string.guardian_open_view_body, app)
        }
    }

    fun openTitle(purpose: OpenPurpose, provider: Provider): String = title(
        when (purpose) {
            OpenPurpose.CANCEL -> UiKey.OPEN_APP_TO_CANCEL
            OpenPurpose.PAY -> UiKey.OPEN_APP_TO_PAY
            OpenPurpose.VIEW -> UiKey.OPEN_APP_TO_VIEW
        },
        listOf(provider),
    )

    fun captureBody(reason: CaptureReason, provider: Provider): String = when (reason) {
        CaptureReason.CHECK_SCREEN -> s(R.string.guardian_capture_check_screen_body, provider(provider))
        CaptureReason.CANCEL_PROOF -> s(R.string.guardian_capture_cancel_proof_body, provider(provider))
        CaptureReason.SHOW_ME -> s(R.string.guardian_capture_show_me_body, provider(provider))
    }

    fun string(@StringRes id: Int, vararg args: Any): String = s(id, *args)

    private companion object {
        /** Labels that already name the app. */
        val SELF_NAMING = setOf(UiAction.OPEN_APP, UiAction.PAY, UiAction.PICK)
    }
}
