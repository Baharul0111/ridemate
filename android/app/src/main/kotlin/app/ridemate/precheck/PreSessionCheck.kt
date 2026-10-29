package app.ridemate.precheck

import app.ridemate.core.model.Provider
import app.ridemate.trip.awaitDetached
import app.ridemate.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * RideWatch (the notification listener) answering "are you connected right now?". Bound in AppGraph to the watch
 * area's `app.ridemate.watch.RideWatchStatus`; without one, [PreSessionCheck] can only read whether notification
 * access is switched on.
 */
fun interface RideWatchPing {
    /** True when RideWatch is connected to Android's notification stream. May suspend briefly; it is time-limited. */
    suspend fun ping(): Boolean
}

/** How much a [PrecheckIssue] stops the comparison. */
enum class IssueSeverity {
    /** Compare cannot start until it is fixed (a fix-it card). */
    BLOCKING,

    /** Compare can start ("Continue anyway"), but something will work less well. */
    WARNING,

    /** Worth knowing; nothing is wrong. */
    INFO,
}

/** Every distinct result of the pre-session check (architecture §4.2) and the two later session outcomes. */
enum class PrecheckKind {
    /** Notification access is off: RideWatch is blind, so no driver or cancel would be noticed. */
    LISTENER_OFF,

    /** Notification access is on, but RideWatch did not answer its ping (Android disconnected it). */
    LISTENER_NOT_RESPONDING,

    /** None of Uber, Ola or Rapido is installed (offer Try the demo). */
    NO_SUPPORTED_APP,

    /** Ride apps are installed but none is selected (and not the open network either). */
    NO_APP_SELECTED,

    /** RideMate may not post notifications: reminders and "open the next app" prompts can't reach the user. */
    NOTIFICATIONS_OFF,

    /** "Display over other apps" is off: no floating button (the ongoing notification mirrors its actions). */
    OVERLAY_OFF,

    /** Battery optimisation may delay reminders. */
    BATTERY_RESTRICTED,

    /** No internet: AI reading is off, prices are typed; opening apps and the guardian still work. */
    OFFLINE,

    /** Location is not allowed: the pickup is the address the user typed. */
    LOCATION_DENIED,
}

/** What a fix-it card's button does. Settings actions have an intent ([FixIntents]); the rest are in-app. */
enum class FixAction {
    OPEN_NOTIFICATION_ACCESS,
    OPEN_OVERLAY_SETTINGS,
    REQUEST_BATTERY_EXEMPTION,
    OPEN_NOTIFICATION_SETTINGS,
    OPEN_INTERNET_SETTINGS,
    REQUEST_LOCATION,

    /** App info, where a sideloaded app's "Allow restricted settings" is (Android 13+). */
    OPEN_APP_INFO,
    TRY_DEMO,
    CHOOSE_APPS,
}

data class PrecheckIssue(val kind: PrecheckKind, val severity: IssueSeverity, val fixes: List<FixAction>)

/** The result of one check, ordered most severe first. */
data class PrecheckReport(val issues: List<PrecheckIssue>, val checkedAtWallMs: Long) {
    val blocking: List<PrecheckIssue> get() = issues.filter { it.severity == IssueSeverity.BLOCKING }

    /** Compare may start (possibly after "Continue anyway" when there are warnings). */
    val canProceed: Boolean get() = blocking.isEmpty()

    /** Nothing to show the user: start right away. */
    val needsAttention: Boolean get() = issues.any { it.severity != IssueSeverity.INFO }

    fun has(kind: PrecheckKind): Boolean = issues.any { it.kind == kind }
}

/** What the comparison is about to do. */
data class PrecheckRequest(
    /** The ride apps the user picked. */
    val apps: Set<Provider>,
    val includeOndc: Boolean = false,
    /** Try the demo: no ride app, no RideWatch, no location needed. */
    val demo: Boolean = false,
)

/** The phone's current permissions and state, read fresh at every check. */
interface PrecheckProbes {
    fun listenerEnabled(): Boolean
    fun overlayAllowed(): Boolean
    fun batteryExempt(): Boolean
    fun notificationsAllowed(): Boolean

    /**
     * The guardian's own channels (questions, loud alerts, reminders) are not switched off one by one: with any of them
     * blocked, a "Cancel the others?" sheet or a still-live alert would never be seen outside RideMate.
     */
    fun guardianChannelsAllowed(): Boolean = true
    fun online(): Boolean
    fun locationAllowed(): Boolean
    fun installedRideApps(): Set<Provider>
}

/**
 * Runs at every Compare tap (goal 20, architecture §4.2 "Pre-session check"): permissions can be switched off at any
 * time, so nothing is assumed from onboarding. Each failure is its own [PrecheckKind] with its own fix-it card:
 *
 * | Result | Severity | Fix |
 * |---|---|---|
 * | RideWatch blind (access off, or no ping answer within [pingTimeoutMs]) | blocking | notification access |
 * | no supported app installed | blocking | Try the demo |
 * | no app selected | blocking | choose apps |
 * | notifications off, or a guardian channel blocked | blocking (warning in the demo): sheets, alerts and reminders reach the user only as notifications | notification settings |
 * | overlay off | warning | overlay settings |
 * | battery optimised | warning | battery exemption |
 * | offline | warning (info in the demo) | internet settings |
 * | location denied | info | allow location |
 *
 * The demo needs no ride app, RideWatch or location. Later outcomes (no prices on a screen, no network quotes) are
 * shown where they happen: the bubble's status line and the open-network panel. The check never hangs: the ping is
 * the only wait and it is time-limited.
 */
class PreSessionCheck(
    private val probes: PrecheckProbes,
    private val rideWatch: () -> RideWatchPing?,
    private val clock: Clock,
    private val pingTimeoutMs: Long = PING_TIMEOUT_MS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun run(request: PrecheckRequest): PrecheckReport {
        val issues = mutableListOf<PrecheckIssue>()
        val snapshot = withContext(io) { Snapshot.read(probes, request.demo) }

        if (!request.demo) {
            listenerIssue(snapshot.listenerEnabled)?.let { issues += it }
            val selected = request.apps.filter { it in Provider.LIVE_APPS }.toSet()
            when {
                snapshot.installed.isEmpty() && !request.includeOndc ->
                    issues += PrecheckIssue(PrecheckKind.NO_SUPPORTED_APP, IssueSeverity.BLOCKING, listOf(FixAction.TRY_DEMO))
                selected.intersect(snapshot.installed).isEmpty() && !request.includeOndc ->
                    issues += PrecheckIssue(PrecheckKind.NO_APP_SELECTED, IssueSeverity.BLOCKING, listOf(FixAction.CHOOSE_APPS))
            }
        }

        if (!snapshot.notifications) {
            // A live comparison's questions, driver sheets, loud alerts and reminders reach the user only as
            // notifications (the bubble shows the cancel guide, nothing else): it can't start without them.
            val severity = if (!request.demo) IssueSeverity.BLOCKING else IssueSeverity.WARNING
            issues += PrecheckIssue(PrecheckKind.NOTIFICATIONS_OFF, severity, listOf(FixAction.OPEN_NOTIFICATION_SETTINGS))
        }
        if (!snapshot.overlay && !request.demo) {
            issues += PrecheckIssue(PrecheckKind.OVERLAY_OFF, IssueSeverity.WARNING, listOf(FixAction.OPEN_OVERLAY_SETTINGS))
        }
        if (!snapshot.battery && !request.demo) {
            issues += PrecheckIssue(PrecheckKind.BATTERY_RESTRICTED, IssueSeverity.WARNING, listOf(FixAction.REQUEST_BATTERY_EXEMPTION))
        }
        if (!snapshot.online) {
            val severity = if (request.demo) IssueSeverity.INFO else IssueSeverity.WARNING
            issues += PrecheckIssue(PrecheckKind.OFFLINE, severity, listOf(FixAction.OPEN_INTERNET_SETTINGS))
        }
        if (!snapshot.location && !request.demo) {
            issues += PrecheckIssue(PrecheckKind.LOCATION_DENIED, IssueSeverity.INFO, listOf(FixAction.REQUEST_LOCATION))
        }
        return PrecheckReport(issues.sortedWith(compareBy({ it.severity.ordinal }, { it.kind.ordinal })), clock.wallMs())
    }

    private suspend fun listenerIssue(enabled: Boolean): PrecheckIssue? {
        if (!enabled) return blind(PrecheckKind.LISTENER_OFF)
        val status = rideWatch() ?: return null // no RideWatch binding: the enabled list is all that can be read
        return if (answersPing(status)) null else blind(PrecheckKind.LISTENER_NOT_RESPONDING)
    }

    /**
     * True only when RideWatch says it is connected within [pingTimeoutMs]. The ping runs detached, so even one that
     * blocks its thread can't hold Compare past the limit; a ping that fails, or gives up with its own internal time
     * limit (a CancellationException of its own), counts as no answer instead of ending the check.
     */
    private suspend fun answersPing(status: RideWatchPing): Boolean =
        withTimeoutOrNull(pingTimeoutMs) {
            try {
                awaitDetached(io) { status.ping() }
            } catch (e: CancellationException) {
                throw e // our own time limit
            } catch (e: Exception) {
                false
            }
        } ?: false

    private fun blind(kind: PrecheckKind) =
        PrecheckIssue(kind, IssueSeverity.BLOCKING, listOf(FixAction.OPEN_NOTIFICATION_ACCESS))

    private data class Snapshot(
        val listenerEnabled: Boolean,
        val overlay: Boolean,
        val battery: Boolean,
        val notifications: Boolean,
        val online: Boolean,
        val location: Boolean,
        val installed: Set<Provider>,
    ) {
        companion object {
            /** Every probe is guarded: a probe that throws counts as "not granted" (never as fine). */
            fun read(p: PrecheckProbes, demo: Boolean) = Snapshot(
                listenerEnabled = !demo && safe { p.listenerEnabled() },
                overlay = safe { p.overlayAllowed() },
                battery = safe { p.batteryExempt() },
                notifications = safe { p.notificationsAllowed() } && (demo || safe { p.guardianChannelsAllowed() }),
                online = safe { p.online() },
                location = safe { p.locationAllowed() },
                installed = if (demo) emptySet() else runCatching { p.installedRideApps() }.getOrDefault(emptySet()),
            )

            private inline fun safe(probe: () -> Boolean): Boolean = try {
                probe()
            } catch (e: Exception) {
                false
            }
        }
    }

    companion object {
        /** How long RideWatch may take to answer before it counts as not responding. */
        const val PING_TIMEOUT_MS: Long = 2_000
    }
}
