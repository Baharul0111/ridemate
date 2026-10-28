package app.ridemate.compare

import android.content.Context
import androidx.annotation.StringRes
import app.ridemate.R
import app.ridemate.bubble.BubbleStatus
import app.ridemate.bubble.StatusKind
import app.ridemate.core.gate.GateText
import app.ridemate.core.model.Provider
import app.ridemate.core.model.VehicleClass
import app.ridemate.net.ApiMessages
import app.ridemate.net.ApiResult

/** Wording for fare-reading outcomes (strings_compare.xml). Every problem says what to do next. */
object FareReadingMessages {

    /** The bubble's status line after a tap. */
    fun status(context: Context, result: ReadFaresResult): BubbleStatus = when (result) {
        is ReadFaresResult.Read -> readStatus(context, result)
        is ReadFaresResult.ManualMode -> BubbleStatus(manualText(context, result), StatusKind.PROBLEM)
        is ReadFaresResult.NotFareScreen ->
            BubbleStatus(context.getString(R.string.compare_not_fare_screen, name(context, result.provider)), StatusKind.PROBLEM)
        is ReadFaresResult.CaptureBlocked ->
            BubbleStatus(context.getString(R.string.compare_capture_blocked, name(context, result.provider)), StatusKind.PROBLEM)
        ReadFaresResult.NeedsConsent -> BubbleStatus(context.getString(R.string.compare_consent_needed), StatusKind.PROBLEM)
        ReadFaresResult.CaptureFailed -> BubbleStatus(context.getString(R.string.compare_capture_failed), StatusKind.PROBLEM)
        is ReadFaresResult.TooSlow -> BubbleStatus(context.getString(R.string.compare_too_slow), StatusKind.PROBLEM)
        ReadFaresResult.TimedOut -> BubbleStatus(context.getString(R.string.compare_timed_out), StatusKind.PROBLEM)
        ReadFaresResult.NoActiveComparison -> BubbleStatus(context.getString(R.string.compare_no_comparison), StatusKind.PROBLEM)
    }

    private fun readStatus(context: Context, result: ReadFaresResult.Read): BubbleStatus {
        val app = name(context, result.provider)
        val verified = result.verifiedCount
        val unreadable = result.unreadableCount
        return when {
            result.rows.isEmpty() -> BubbleStatus(context.getString(R.string.compare_no_prices_found, app), StatusKind.PROBLEM)
            verified == 0 -> BubbleStatus(context.getString(R.string.compare_none_verified, app), StatusKind.PROBLEM)
            unreadable == 0 -> BubbleStatus(
                context.resources.getQuantityString(R.plurals.compare_prices_checked, verified, verified, app),
                StatusKind.INFO,
            )
            else -> BubbleStatus(
                context.resources.getQuantityString(R.plurals.compare_prices_checked, verified, verified, app) + " " +
                    context.resources.getQuantityString(R.plurals.compare_rows_unreadable, unreadable, unreadable),
                StatusKind.INFO,
            )
        }
    }

    /** "AI reading resumes at 1:30 pm (IST). You can type the price meanwhile." or the reason's own wording. */
    fun manualText(context: Context, result: ReadFaresResult.ManualMode): String {
        result.apiResult?.let { api -> ApiMessages.text(context, api)?.let { return it } }
        if (result.resumeAtIst != null) {
            ApiMessages.text(context, ApiResult.AiPaused("ai_paused", result.resumeAtIst, null))?.let { return it }
        }
        return when (result.reason) {
            ManualReason.SIGNED_OUT, ManualReason.AUTH_NEEDED -> context.getString(R.string.platform_sign_in_needed)
            ManualReason.ANONYMOUS, ManualReason.GOOGLE_REQUIRED -> context.getString(R.string.platform_google_required)
            ManualReason.KILL_SWITCH -> context.getString(R.string.compare_manual_switched_off)
            ManualReason.READER_UNAVAILABLE -> context.getString(R.string.compare_manual_reader_unavailable, name(context, result.provider))
            ManualReason.AI_PAUSED -> context.getString(R.string.platform_ai_paused)
            ManualReason.RATE_LIMITED -> context.getString(R.string.platform_rate_limited)
            ManualReason.OFFLINE -> context.getString(R.string.platform_offline)
            ManualReason.TIMEOUT -> context.getString(R.string.platform_timeout)
            ManualReason.NOT_CONFIGURED -> context.getString(R.string.platform_not_configured)
            ManualReason.SERVER_ERROR -> context.getString(R.string.platform_server_error)
        }
    }

    /** The provider's name as shown to the user (demo providers say DEMO; goal 19). */
    fun name(context: Context, provider: Provider): String = when (provider) {
        Provider.ONDC -> context.getString(R.string.compare_provider_ondc)
        else -> if (provider.isDemo) context.getString(R.string.compare_provider_demo, provider.displayName) else provider.displayName
    }

    @StringRes
    fun vehicleClassRes(vehicleClass: VehicleClass): Int = when (vehicleClass) {
        VehicleClass.BIKE -> R.string.compare_class_bike
        VehicleClass.AUTO -> R.string.compare_class_auto
        VehicleClass.CAB_ECONOMY -> R.string.compare_class_cab_economy
        VehicleClass.CAB_PREMIUM -> R.string.compare_class_cab_premium
        VehicleClass.CAB_XL -> R.string.compare_class_cab_xl
        VehicleClass.OTHER -> R.string.compare_class_other
    }

    /** Core [GateText] keys → this app's string resources (never looked up by name at run time). */
    @StringRes
    fun gateTextRes(key: String): Int = when (key) {
        GateText.UNREADABLE -> R.string.quote_unreadable
        GateText.ETA_SEE_IN_APP -> R.string.eta_see_in_app
        GateText.DRIVER_SEE_IN_APP -> R.string.driver_see_in_app
        GateText.ENTERED_BY_YOU -> R.string.quote_entered_by_you
        GateText.NETWORK_QUOTE -> R.string.quote_network
        GateText.SEEN_AT -> R.string.quote_seen_at
        GateText.STALE -> R.string.quote_stale
        GateText.CHEAPEST -> R.string.quote_cheapest
        else -> throw IllegalArgumentException("unknown gate text key")
    }
}
