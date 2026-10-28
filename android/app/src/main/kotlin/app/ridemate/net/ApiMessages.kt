package app.ridemate.net

import android.content.Context
import androidx.annotation.StringRes
import app.ridemate.R
import app.ridemate.data.StorageWarning
import app.ridemate.util.Ist
import java.util.Locale

/** User-facing wording for platform outcomes (strings_platform.xml); every text says what to do next. */
object ApiMessages {

    /** The message for a failed call, or null for [ApiResult.Ok]. */
    fun text(context: Context, result: ApiResult<*>, locale: Locale = Locale.getDefault()): String? {
        if (result is ApiResult.AiPaused) {
            val resumeAt = Ist.formatResumeAt(result.resumeAtIst, locale)
            if (resumeAt != null) return context.getString(R.string.platform_ai_paused_until, resumeAt)
        }
        return resFor(result)?.let(context::getString)
    }

    @StringRes
    fun resFor(result: ApiResult<*>): Int? = when (result) {
        is ApiResult.Ok -> null
        is ApiResult.AiPaused -> R.string.platform_ai_paused
        ApiResult.AuthNeeded -> R.string.platform_sign_in_needed
        ApiResult.GoogleRequired -> R.string.platform_google_required
        is ApiResult.RateLimited -> R.string.platform_rate_limited
        ApiResult.Offline -> R.string.platform_offline
        ApiResult.Timeout -> R.string.platform_timeout
        ApiResult.NotConfigured -> R.string.platform_not_configured
        is ApiResult.Http -> if (result.code == 413) R.string.platform_too_large else R.string.platform_server_error
    }

    @StringRes
    fun storageWarningRes(warning: StorageWarning): Int? = when (warning) {
        StorageWarning.NONE -> null
        StorageWarning.FULL -> R.string.platform_storage_full
        StorageWarning.WRITE_FAILED -> R.string.platform_storage_write_failed
    }
}
