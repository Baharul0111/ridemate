package app.ridemate.launch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import app.ridemate.core.model.Provider
import app.ridemate.trip.TripPlace
import app.ridemate.util.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Open [provider]'s app for the trip [pickup] → [drop]; [legId] is the session leg to mark OPENED (if any). */
data class LaunchRequest(
    val provider: Provider,
    val pickup: TripPlace,
    val drop: TripPlace,
    val legId: String? = null,
)

enum class LaunchOutcome {
    /** The ride app was opened. */
    OPENED,

    /** Android would not let RideMate open it from the background: a heads-up notification asks the user to tap. */
    NOTIFICATION_POSTED,

    /** Neither opening nor notifying was possible (notifications off): the user must open the app themselves. */
    BLOCKED,

    /** The app is not installed (or has no launcher activity). */
    NOT_INSTALLED,

    /** Demo providers and ONDC are never opened as apps. */
    NOT_A_RIDE_APP,

    /** Android refused to start the app. */
    FAILED,
}

enum class LaunchMethod {
    /** The deep link with the route filled in (Uber). */
    DEEP_LINK,

    /** The app's launcher activity, as configured. */
    PLAIN,

    /** The launcher activity because the deep link could not be handled. */
    PLAIN_FALLBACK,
}

data class LaunchResult(
    val provider: Provider,
    val outcome: LaunchOutcome,
    /** How it was opened; null unless [outcome] is OPENED. */
    val method: LaunchMethod? = null,
    /** The drop address was put on the clipboard for the "Paste drop" chip. */
    val dropCopied: Boolean = false,
)

/** Starts an activity; returns false if Android refused. */
fun interface ActivityStarter {
    fun start(intent: Intent): Boolean
}

/** [ActivityStarter] from the application context (adds FLAG_ACTIVITY_NEW_TASK). */
class ContextActivityStarter(private val context: Context) : ActivityStarter {
    override fun start(intent: Intent): Boolean = try {
        context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

/**
 * Opens each ride app with the route ready (goal 3, architecture §4.2 AppLauncher). RideMate only *opens* the apps
 * with intents: it never taps, types or reads inside them.
 *
 * - **Uber**: the `launch_templates` universal link (`https://m.uber.com/ul/?action=setPickup&pickup[latitude]=…`)
 *   with URL-encoded coordinates and nicknames and **no `client_id`**, restricted to Uber's package. If Uber can't
 *   handle it (or the trip has no coordinates), the configured fallback: a plain launch.
 * - **Ola / Rapido**: `getLaunchIntentForPackage` plus the drop address on the clipboard (label "RideMate drop",
 *   marked not sensitive) and the bubble's "Paste drop" chip ([pasteDrop]).
 * - **Android 15 background-start rule**: an app is opened directly only while the bubble is visible or RideMate
 *   is in the foreground. Otherwise a heads-up notification is posted; its tap opens [OpenRideAppActivity], which calls
 *   [openFromUserTap].
 * - Every successful open records the leg as OPENED through [legOpened].
 */
class AppLauncher(
    private val templates: LaunchTemplateProvider,
    private val apps: InstalledApps,
    private val starter: ActivityStarter,
    private val notifier: LaunchNotifier,
    private val clipboard: ClipboardWriter,
    private val bubble: BubbleVisibility,
    private val foreground: AppForeground,
    private val legOpened: LegOpenedSink,
    private val clock: Clock,
) {
    private val _pasteDrop = MutableStateFlow<PasteDropChip?>(null)

    /** The drop address the bubble's "Paste drop" chip offers for the app opened last; null when none applies. */
    val pasteDrop: StateFlow<PasteDropChip?> = _pasteDrop.asStateFlow()

    /** The plan [open] would follow for [request] (no side effects). */
    fun plan(request: LaunchRequest): LaunchPlan =
        LaunchPlanner.plan(request.provider, templates.templates(), request.pickup, request.drop)

    /** True when Android lets RideMate open another app right now (bubble visible or RideMate in the foreground). */
    fun canOpenDirectly(): Boolean = bubble.isShowing || foreground.isForeground

    /**
     * Opens [request]'s app now if Android allows it ([canOpenDirectly]), else posts the heads-up notification.
     * Call it from the bubble, the cancel chain or the comparison screen.
     */
    suspend fun open(request: LaunchRequest): LaunchResult = launch(request, userTap = false)

    /** Opens [request]'s app immediately. Only for a caller that is itself in the foreground because the user tapped. */
    suspend fun openFromUserTap(request: LaunchRequest): LaunchResult = launch(request, userTap = true)

    /** The "Paste drop" chip was tapped: copies its address again. False when there is no chip or copying failed. */
    fun copyDropAgain(): Boolean {
        val chip = _pasteDrop.value ?: return false
        val ok = clipboard.copyDrop(chip.dropText)
        if (ok) _pasteDrop.value = chip.copy(copiedAtWallMs = clock.wallMs())
        return ok
    }

    /** Hides the chip (session ended, or the user dismissed it). */
    fun clearPasteDrop() {
        _pasteDrop.value = null
    }

    private suspend fun launch(request: LaunchRequest, userTap: Boolean): LaunchResult {
        val provider = request.provider
        if (provider !in Provider.LIVE_APPS) return LaunchResult(provider, LaunchOutcome.NOT_A_RIDE_APP)
        if (!apps.isInstalled(provider)) return LaunchResult(provider, LaunchOutcome.NOT_INSTALLED)

        if (!userTap && !canOpenDirectly()) {
            val posted = notifier.postOpenApp(request)
            return LaunchResult(provider, if (posted) LaunchOutcome.NOTIFICATION_POSTED else LaunchOutcome.BLOCKED)
        }
        notifier.cancel(provider)

        val result = when (val plan = plan(request)) {
            is LaunchPlan.DeepLink -> {
                val link = Intent(Intent.ACTION_VIEW, plan.uri.toUri())
                    .setPackage(provider.packageName)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                if (apps.canHandle(link) && starter.start(link)) {
                    showChip(provider, null)
                    LaunchResult(provider, LaunchOutcome.OPENED, LaunchMethod.DEEP_LINK)
                } else {
                    plainLaunch(provider, plan.fallbackPasteDrop, LaunchMethod.PLAIN_FALLBACK)
                }
            }
            is LaunchPlan.Launch -> plainLaunch(provider, plan.pasteDrop, LaunchMethod.PLAIN)
        }
        if (result.outcome == LaunchOutcome.OPENED && request.legId != null) {
            legOpened.legOpened(request.legId, provider, clock.wallMs())
        }
        return result
    }

    private fun plainLaunch(provider: Provider, pasteDrop: String?, method: LaunchMethod): LaunchResult {
        val intent = apps.launchIntent(provider) ?: return LaunchResult(provider, LaunchOutcome.NOT_INSTALLED)
        // The address goes on the clipboard before the app opens, so it is ready when the user taps the drop box.
        val copied = pasteDrop != null && clipboard.copyDrop(pasteDrop)
        if (!starter.start(intent)) return LaunchResult(provider, LaunchOutcome.FAILED, dropCopied = copied)
        showChip(provider, pasteDrop.takeIf { copied })
        return LaunchResult(provider, LaunchOutcome.OPENED, method, dropCopied = copied)
    }

    private fun showChip(provider: Provider, dropText: String?) {
        _pasteDrop.value = dropText?.let { PasteDropChip(provider, it, clock.wallMs()) }
    }
}
