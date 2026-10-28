package app.ridemate.home

/** A reason Compare cannot start, or should warn first (architecture §4.2 Pre-session check). */
enum class PreSessionIssue {
    /** RideWatch is blind: notification access is off or the listener is disconnected. Blocks Compare. */
    LISTENER_OFF,

    /** No floating button: display over other apps is off (the ongoing notification mirrors its actions). */
    OVERLAY_OFF,

    /** Reminders could not be shown. Blocks Compare. */
    NOTIFICATIONS_OFF,

    /** None of Uber, Ola, Rapido is installed: offer Try the demo. Blocks Compare. */
    NO_SUPPORTED_APP,

    /** Battery optimisation may delay reminders. Warning. */
    BATTERY_OPTIMISED,

    /** Pickup must be typed. Warning. */
    LOCATION_OFF,

    /** No connection: ride apps and AI reading need one. Warning. */
    OFFLINE,
}

/** The outcome of the pre-session check: Compare starts only when [blocking] is empty. */
data class PreSessionReport(val blocking: List<PreSessionIssue>, val warnings: List<PreSessionIssue>) {
    val canStart: Boolean get() = blocking.isEmpty()
    val clean: Boolean get() = blocking.isEmpty() && warnings.isEmpty()

    companion object {
        val READY = PreSessionReport(emptyList(), emptyList())
    }
}

/** Runs before every Compare tap: bound to A2's PreSessionCheck (`app.ridemate.integration.PrecheckGate`). */
fun interface PreSessionGate {
    suspend fun check(): PreSessionReport
}
