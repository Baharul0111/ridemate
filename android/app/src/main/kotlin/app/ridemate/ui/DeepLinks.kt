package app.ridemate.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.ridemate.MainActivity

/** Where an intent from a notification (or the launcher) asks the app to go. */
sealed interface DeepLink {
    /** The guardian of [sessionId]; [sheet] is an opaque sheet id the guardian understands (e.g. "first_driver"). */
    data class Guardian(val sessionId: String?, val sheet: String?) : DeepLink
}

/**
 * Intents into [MainActivity] for other areas' notifications (A3: driver found, reminders, "cancel the others?").
 * Build them with [guardian]; [parse] validates every extra (MainActivity is exported as the launcher, so extras
 * may come from anywhere; they only ever choose a screen).
 */
object DeepLinks {
    const val ACTION_OPEN_GUARDIAN = "app.ridemate.action.OPEN_GUARDIAN"
    const val EXTRA_SESSION_ID = "app.ridemate.extra.SESSION_ID"
    const val EXTRA_SHEET = "app.ridemate.extra.SHEET"

    private val SAFE_ID = Regex("^[A-Za-z0-9._:-]{1,80}$")

    /** Opens the guardian (and optionally one of its sheets) on top of whatever RideMate shows. */
    fun guardian(context: Context, sessionId: String?, sheet: String? = null): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_GUARDIAN)
            .apply {
                sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
                sheet?.let { putExtra(EXTRA_SHEET, it) }
                // Distinct PendingIntents per session/sheet without relying on request codes.
                data = Uri.Builder().scheme("ridemate").authority("guardian")
                    .appendPath(sessionId ?: "current").appendPath(sheet ?: "none").build()
            }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun parse(intent: Intent?): DeepLink? {
        if (intent?.action != ACTION_OPEN_GUARDIAN) return null
        val session = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf { SAFE_ID.matches(it) }
        val sheet = intent.getStringExtra(EXTRA_SHEET)?.takeIf { SAFE_ID.matches(it) }
        return DeepLink.Guardian(session, sheet)
    }
}
