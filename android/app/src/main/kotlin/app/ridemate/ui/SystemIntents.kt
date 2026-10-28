package app.ridemate.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri

/**
 * Opening other screens with intents only (settings, browser, email). RideMate never acts inside other apps; it
 * only asks Android to show a screen, and the user does the rest.
 */
object SystemIntents {

    /** Starts the first of [intents] that Android can open; false when none could be opened. */
    fun startFirst(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            val launch = if (context !is Activity) Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent
            try {
                context.startActivity(launch)
                return true
            } catch (_: ActivityNotFoundException) {
                // try the next one
            } catch (_: SecurityException) {
                // an OEM screen we may not open; try the next one
            }
        }
        return false
    }

    /** Opens an https link in the user's browser. Only https is allowed. */
    fun openHttps(context: Context, url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return false
        return startFirst(context, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    /** A `mailto:` intent with the subject and body in the URI (some mail apps ignore extras) and as extras. */
    fun mailto(address: String, subject: String, body: String?): Intent {
        val query = buildString {
            append("?subject=").append(Uri.encode(subject))
            if (body != null) append("&body=").append(Uri.encode(body))
        }
        return Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address$query"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .apply { if (body != null) putExtra(Intent.EXTRA_TEXT, body) }
    }

    /** Opens the user's email app; false when no email app is installed. */
    fun sendEmail(context: Context, address: String, subject: String, body: String?): Boolean =
        startFirst(context, mailto(address, subject, body))
}

/** The Activity behind a Compose context (null in previews and plain-context tests). */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
