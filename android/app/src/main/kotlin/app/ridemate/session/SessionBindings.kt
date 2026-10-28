package app.ridemate.session

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import app.ridemate.core.guardian.OpenPurpose
import app.ridemate.core.model.Provider
import app.ridemate.notify.GuardianNotifier
import app.ridemate.notify.GuardianPresenter

/**
 * The pieces other areas plug into the guardian at integration time (bubble, launcher, capture, ONDC, compare). Each
 * starts with a working default: no bubble (every action stays on the ongoing notification), apps opened by their
 * launch intent, no capture requester (the card explains the share-a-screenshot fallback), no ONDC sink.
 */
class SessionBindings(context: Context) {
    @Volatile
    var bubble: GuardianBubble = GuardianBubble.NONE

    @Volatile
    var opener: RideAppOpener = PackageLaunchOpener(context.applicationContext)

    @Volatile
    var capture: CaptureRequester? = null

    @Volatile
    var ondc: OndcCommandSink? = null

    @Volatile
    var compareAgain: CompareAgainHandler? = null

    @Volatile
    var fareReader: FareReadRequester? = null
}

/** Opens a live ride app by its launch intent. Demo providers and ONDC have no app to open. */
class PackageLaunchOpener(private val context: Context) : RideAppOpener {
    override fun intentFor(provider: Provider, purpose: OpenPurpose): Intent? {
        if (provider.isDemo || provider.isNetwork) return null
        return context.packageManager.getLaunchIntentForPackage(provider.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}

/** What happened when RideMate tried to open an app. */
enum class OpenOutcome {
    /** Opened directly: RideMate was in front, or the bubble was visible (Android 15 background-start rule). */
    OPENED,

    /** Not allowed (or not possible) right now: a heads-up "Open <App> …" notification whose tap opens it. */
    NOTIFIED,
}

/**
 * Opens a ride app only under the background-start rule (§4.2 AppLauncher): directly while RideMate is in the
 * foreground or its bubble is visible, otherwise through a heads-up notification the user taps. RideMate only opens
 * the app; the user does every tap inside it.
 */
class GuardianAppOpener(
    private val context: Context,
    private val bindings: SessionBindings,
    private val notifier: GuardianNotifier,
    private val presenter: () -> GuardianPresenter,
    private val appInForeground: () -> Boolean,
) {
    fun open(legId: String, provider: Provider, purpose: OpenPurpose): OpenOutcome {
        val intent = bindings.opener.intentFor(provider, purpose)
        if (intent != null && (appInForeground() || bindings.bubble.isVisible)) {
            try {
                context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                notifier.cancel(openCardId(legId))
                return OpenOutcome.OPENED
            } catch (_: ActivityNotFoundException) {
                // Uninstalled since the session started: fall through to the notification, which says so.
            } catch (_: SecurityException) {
                // Blocked by the platform: the notification lets the user open it with a tap.
            }
        }
        notifier.post(presenter().openApp(legId, provider, purpose, hasApp = intent != null))
        return OpenOutcome.NOTIFIED
    }

    private fun openCardId(legId: String) = "open:$legId"
}

/** The opt-in "Keep searching" setting (off by default, with a fee and driver-harm warning). */
class KeepSearchingPreference(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(value) = prefs.edit { putBoolean(KEY, value) }

    fun clear() = prefs.edit { clear() }

    companion object {
        const val PREFS = "ridemate_guardian_prefs"
        private const val KEY = "keep_searching"
    }
}

/** Open sheets and questions of the active session, kept next to the guardian snapshot so they survive process death. */
class UiJournalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(sessionId: String, json: String) = prefs.edit { clear().putString(sessionId, json) }

    fun load(sessionId: String): String? = prefs.getString(sessionId, null)

    fun clear() = prefs.edit { clear() }

    companion object {
        const val PREFS = "ridemate_guardian_ui"
    }
}
