package app.ridemate.demo

import android.content.Context
import androidx.annotation.StringRes
import app.ridemate.R
import app.ridemate.core.format.IndianFormat
import app.ridemate.core.format.IstTime
import app.ridemate.core.gate.ComparisonEntry
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.VehicleClass

/** Every demo wording comes from strings_demo.xml (English + Hindi); model output never becomes text (I5). */
object DemoText {

    @StringRes
    fun trackTitle(choice: DemoTrackChoice): Int = when (choice) {
        DemoTrackChoice.MAIN -> R.string.demo_track_main_title
        DemoTrackChoice.KEEP_SEARCHING -> R.string.demo_track_keep_searching_title
        DemoTrackChoice.REMATCH -> R.string.demo_track_rematch_title
    }

    @StringRes
    fun trackBody(choice: DemoTrackChoice): Int = when (choice) {
        DemoTrackChoice.MAIN -> R.string.demo_track_main_body
        DemoTrackChoice.KEEP_SEARCHING -> R.string.demo_track_keep_searching_body
        DemoTrackChoice.REMATCH -> R.string.demo_track_rematch_body
    }

    @StringRes
    fun unavailable(reason: DemoUnavailable): Int = when (reason) {
        DemoUnavailable.NOT_READY -> R.string.demo_unavailable_not_ready
        DemoUnavailable.SWITCHED_OFF -> R.string.demo_unavailable_switched_off
        DemoUnavailable.SESSION_BUSY -> R.string.demo_unavailable_session_busy
        DemoUnavailable.ASSETS_BROKEN -> R.string.demo_unavailable_assets_broken
    }

    @StringRes
    fun notice(notice: DemoNotice): Int = when (notice) {
        DemoNotice.NOT_NOW_CHOSEN -> R.string.demo_notice_not_now
        DemoNotice.SHOW_ME_UNAVAILABLE -> R.string.demo_notice_show_me_unavailable
        DemoNotice.SWITCHED_OFF_WHILE_RUNNING -> R.string.demo_notice_switched_off
    }

    @StringRes
    fun screenName(screen: String): Int = when (screen) {
        DemoScreens.FARE_OPTIONS -> R.string.demo_screen_fare_options
        DemoScreens.SEARCHING -> R.string.demo_screen_searching
        DemoScreens.DRIVER_ASSIGNED -> R.string.demo_screen_driver_assigned
        "driver_assigned_better" -> R.string.demo_screen_driver_assigned_better
        DemoScreens.CANCEL_FLOW -> R.string.demo_screen_cancel_flow
        DemoScreens.CANCELLED -> R.string.demo_screen_cancelled
        else -> R.string.demo_screen_other
    }

    /** The short source label on a read ("recorded" for a recorded read, M9). */
    @StringRes
    fun source(source: ReadSource): Int = when (source) {
        ReadSource.LIVE -> R.string.demo_source_live
        ReadSource.CACHE -> R.string.demo_source_cache
        ReadSource.RECORDED -> R.string.demo_source_recorded
        ReadSource.NONE -> R.string.demo_source_none
    }

    /** What to tell the user about a read, or null when it went normally. */
    @StringRes
    fun issue(result: DemoReadResult): Int? = when (result.issue) {
        DemoReadIssue.NONE -> if (result.recordedOnPhone) R.string.demo_issue_recorded_fallback else null
        DemoReadIssue.AI_SWITCHED_OFF -> R.string.demo_issue_ai_switched_off
        DemoReadIssue.AI_UNAVAILABLE -> R.string.demo_issue_ai_unavailable
        DemoReadIssue.NOT_A_FARE_SCREEN -> R.string.demo_issue_not_a_fare_screen
        DemoReadIssue.OCR_FAILED -> R.string.demo_issue_ocr_failed
        DemoReadIssue.NO_PARSER -> R.string.demo_issue_no_parser
    }

    @StringRes
    fun vehicleClass(vehicleClass: VehicleClass): Int = when (vehicleClass) {
        VehicleClass.BIKE -> R.string.demo_class_bike
        VehicleClass.AUTO -> R.string.demo_class_auto
        VehicleClass.CAB_ECONOMY -> R.string.demo_class_cab_economy
        VehicleClass.CAB_PREMIUM -> R.string.demo_class_cab_premium
        VehicleClass.CAB_XL -> R.string.demo_class_cab_xl
        VehicleClass.OTHER -> R.string.demo_class_other
    }

    /** A provider's status in the overview, in words. */
    @StringRes
    fun status(view: DemoProviderView): Int = when {
        !view.booked -> R.string.demo_status_not_booked
        view.cancelProven || view.screen == DemoScreens.CANCELLED -> R.string.demo_status_cancelled
        view.cancelTapped -> R.string.demo_status_cancelling
        view.screen == DemoScreens.SEARCHING -> R.string.demo_status_searching
        view.screen.startsWith(DemoScreens.DRIVER_ASSIGNED) -> R.string.demo_status_driver
        else -> R.string.demo_status_booked
    }

    /** "Kestrel Go · DEMO". */
    fun providerName(context: Context, provider: Provider): String =
        context.getString(R.string.demo_provider_label, provider.displayName)

    /** The row's label: the OCR label when there is one, else the localised class name. */
    fun rowLabel(context: Context, entry: ComparisonEntry): String =
        if (entry.row.labelFromOcr) entry.row.displayLabel else context.getString(vehicleClass(entry.row.vehicleClass))

    fun rowPrice(context: Context, entry: ComparisonEntry): String =
        entry.priceText ?: context.getString(R.string.demo_row_unreadable)

    fun rowEta(context: Context, entry: ComparisonEntry): String? = when {
        entry.row.status == QuoteStatus.UNREADABLE -> null
        entry.row.eta != null -> context.getString(R.string.demo_row_eta, entry.row.eta)
        else -> context.getString(R.string.demo_row_eta_unknown)
    }

    /** "seen in Kestrel Go at 1:05:09 PM" (IST, 12-hour). */
    fun rowSeenAt(context: Context, entry: ComparisonEntry): String =
        context.getString(R.string.demo_row_seen_at, entry.row.provider.displayName, IstTime.seenAt(entry.row.seenAtMs))

    /** One sentence a screen reader can read for the row (state in words, never colour alone). */
    fun rowDescription(context: Context, entry: ComparisonEntry): String {
        val extras = listOfNotNull(
            rowEta(context, entry),
            context.getString(R.string.demo_row_cheapest).takeIf { entry.cheapest },
        ).joinToString(", ")
        return listOfNotNull(
            context.getString(R.string.demo_row_description, rowLabel(context, entry), rowPrice(context, entry)),
            extras,
            rowSeenAt(context, entry),
            context.getString(R.string.demo_row_stale).takeIf { entry.stale },
        ).filter { it.isNotBlank() }.joinToString(". ")
    }

    /** Booked fare text for fee notes ("₹172"). */
    fun rupees(value: Int): String = IndianFormat.rupees(value.toLong())
}
