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
    }

    @StringRes
    fun fixLabel(action: FixAction): Int = when (action) {
        FixAction.OPEN_NOTIFICATION_ACCESS -> R.string.precheck_fix_notification_access
        FixAction.OPEN_OVERLAY_SETTINGS -> R.string.precheck_fix_overlay
        FixAction.REQUEST_BATTERY_EXEMPTION -> R.string.precheck_fix_battery
        FixAction.OPEN_NOTIFICATION_SETTINGS -> R.string.precheck_fix_notifications
        FixAction.OPEN_INTERNET_SETTINGS -> R.string.precheck_fix_internet
        FixAction.REQUEST_LOCATION -> R.string.precheck_fix_location
        FixAction.OPEN_APP_INFO -> R.string.onb_restricted_open_app_info
        FixAction.TRY_DEMO -> R.string.precheck_fix_demo
        FixAction.CHOOSE_APPS -> R.string.precheck_fix_choose_apps
    }

    /** The severity in words, so it is never shown by colour alone (goal 27). */
    @StringRes
    fun severityLabel(severity: IssueSeverity): Int = when (severity) {
        IssueSeverity.BLOCKING -> R.string.precheck_severity_blocking
        IssueSeverity.WARNING -> R.string.precheck_severity_warning
        IssueSeverity.INFO -> R.string.precheck_severity_info
    }

    /** Kinds whose system switch Android 13+ greys out for a sideloaded app until "Allow restricted settings". */
    val RESTRICTED_SWITCHES: Set<PrecheckKind> =
        setOf(PrecheckKind.LISTENER_OFF, PrecheckKind.LISTENER_NOT_RESPONDING, PrecheckKind.OVERLAY_OFF)
}
