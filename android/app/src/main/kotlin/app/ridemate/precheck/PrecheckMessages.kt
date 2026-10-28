package app.ridemate.precheck

import androidx.annotation.StringRes
import app.ridemate.R

/** Wording for every pre-session result (strings_precheck.xml). Each body says what to do next. */
object PrecheckMessages {

    @StringRes
    fun title(kind: PrecheckKind): Int = when (kind) {
        PrecheckKind.LISTENER_OFF -> R.string.precheck_listener_off_title
        PrecheckKind.LISTENER_NOT_RESPONDING -> R.string.precheck_listener_silent_title
        PrecheckKind.NO_SUPPORTED_APP -> R.string.precheck_no_app_title
        PrecheckKind.NO_APP_SELECTED -> R.string.precheck_none_selected_title
        PrecheckKind.NOTIFICATIONS_OFF -> R.string.precheck_notifications_title
        PrecheckKind.OVERLAY_OFF -> R.string.precheck_overlay_title
        PrecheckKind.BATTERY_RESTRICTED -> R.string.precheck_battery_title
        PrecheckKind.OFFLINE -> R.string.precheck_offline_title
        PrecheckKind.LOCATION_DENIED -> R.string.precheck_location_title
        PrecheckKind.NO_FARES_FOUND -> R.string.precheck_no_fares_title
        PrecheckKind.NO_ONDC_RESULTS -> R.string.precheck_no_ondc_title
    }

    @StringRes
    fun body(kind: PrecheckKind): Int = when (kind) {
        PrecheckKind.LISTENER_OFF -> R.string.precheck_listener_off_body
        PrecheckKind.LISTENER_NOT_RESPONDING -> R.string.precheck_listener_silent_body
        PrecheckKind.NO_SUPPORTED_APP -> R.string.precheck_no_app_body
        PrecheckKind.NO_APP_SELECTED -> R.string.precheck_none_selected_body
        PrecheckKind.NOTIFICATIONS_OFF -> R.string.precheck_notifications_body
        PrecheckKind.OVERLAY_OFF -> R.string.precheck_overlay_body
        PrecheckKind.BATTERY_RESTRICTED -> R.string.precheck_battery_body
        PrecheckKind.OFFLINE -> R.string.precheck_offline_body
        PrecheckKind.LOCATION_DENIED -> R.string.precheck_location_body
        PrecheckKind.NO_FARES_FOUND -> R.string.precheck_no_fares_body
        PrecheckKind.NO_ONDC_RESULTS -> R.string.precheck_no_ondc_body
    }

    @StringRes
    fun fixLabel(action: FixAction): Int = when (action) {
        FixAction.OPEN_NOTIFICATION_ACCESS -> R.string.precheck_fix_notification_access
        FixAction.OPEN_OVERLAY_SETTINGS -> R.string.precheck_fix_overlay
        FixAction.REQUEST_BATTERY_EXEMPTION -> R.string.precheck_fix_battery
        FixAction.OPEN_NOTIFICATION_SETTINGS -> R.string.precheck_fix_notifications
        FixAction.OPEN_INTERNET_SETTINGS -> R.string.precheck_fix_internet
        FixAction.REQUEST_LOCATION -> R.string.precheck_fix_location
        FixAction.TRY_DEMO -> R.string.precheck_fix_demo
        FixAction.CHOOSE_APPS -> R.string.precheck_fix_choose_apps
        FixAction.RETRY_READ -> R.string.precheck_fix_retry_read
        FixAction.TYPE_PRICE -> R.string.precheck_fix_type_price
        FixAction.CONTINUE_WITHOUT_ONDC -> R.string.precheck_fix_without_ondc
    }

    /** The severity in words, so it is never shown by colour alone (goal 27). */
    @StringRes
    fun severityLabel(severity: IssueSeverity): Int = when (severity) {
        IssueSeverity.BLOCKING -> R.string.precheck_severity_blocking
        IssueSeverity.WARNING -> R.string.precheck_severity_warning
        IssueSeverity.INFO -> R.string.precheck_severity_info
    }
}
