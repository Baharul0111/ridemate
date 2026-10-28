package app.ridemate.update

import android.content.Context
import app.ridemate.R

/** Wording for the update banner / blocking screen (strings_platform.xml). */
object UpdateMessages {
    /** The banner or blocking text; null when there is nothing to say. */
    fun headline(context: Context, state: UpdateState): String? = when {
        state.forceUpdate -> context.getString(R.string.platform_update_forced)
        state.available && state.versionName != null -> context.getString(R.string.platform_update_available, state.versionName)
        else -> null
    }

    /** "SHA-256: …" so the user can check the downloaded APK; null without a published checksum. */
    fun checksum(context: Context, state: UpdateState): String? =
        state.sha256?.let { context.getString(R.string.platform_update_checksum, it) }
}
