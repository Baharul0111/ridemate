package app.ridemate.privacy

import android.content.Context
import androidx.annotation.StringRes
import app.ridemate.R
import app.ridemate.ui.UiFormat
import java.util.Locale

/** Wording for the My data and Erase screens (strings_platform.xml). */
object PrivacyMessages {

    @StringRes
    fun heldDataLabelRes(kind: HeldData): Int = when (kind) {
        HeldData.SESSIONS -> R.string.platform_my_data_sessions
        HeldData.LEGS -> R.string.platform_my_data_legs
        HeldData.QUOTES -> R.string.platform_my_data_quotes
        HeldData.EVIDENCE -> R.string.platform_my_data_evidence
        HeldData.PENDING_ACTIONS -> R.string.platform_my_data_pending_actions
        HeldData.NOTIFICATION_FINGERPRINTS -> R.string.platform_my_data_fingerprints
        HeldData.CLASSIFIER_CACHE -> R.string.platform_my_data_classifier
        HeldData.GUARDIAN_SNAPSHOTS -> R.string.platform_my_data_snapshots
        HeldData.RECORDER_POSTS -> R.string.platform_my_data_recorder
    }

    /** The lines of the My data screen, in display order (counts, sign-in, last session, settings, pending erase). */
    fun myDataLines(context: Context, summary: MyDataSummary, locale: Locale = Locale.getDefault()): List<String> = buildList {
        summary.counts.forEach { (kind, count) -> add("${context.getString(heldDataLabelRes(kind))}: $count") }
        add(
            when (summary.signInKind) {
                SignInKind.GOOGLE -> context.getString(R.string.platform_my_data_signed_in_as, summary.signInEmail.orEmpty())
                SignInKind.ANONYMOUS -> context.getString(R.string.platform_my_data_anonymous)
                SignInKind.SIGNED_OUT -> context.getString(R.string.platform_my_data_signed_out)
            },
        )
        summary.lastSessionAtUtc?.let { add(context.getString(R.string.platform_my_data_last_session, UiFormat.dateTimeIst(it, locale))) }
        val config = summary.remoteConfig
        if (config.remoteFingerprint != null && config.lastFetchUtcMs != null) {
            add(context.getString(R.string.platform_my_data_config, config.remoteFingerprint, UiFormat.dateTimeIst(config.lastFetchUtcMs, locale)))
        } else {
            add(context.getString(R.string.platform_my_data_config_bundled))
        }
        if (summary.pendingServerErase) add(context.getString(R.string.platform_my_data_erase_pending))
    }

    /** What to tell the user about an erase; null while idle or running. [privacyEmail] is the grievance contact. */
    fun eraseStatusText(context: Context, status: EraseStatus, privacyEmail: String): String? = when (status) {
        EraseStatus.Idle, EraseStatus.Running -> null
        EraseStatus.WaitingForServer -> context.getString(R.string.platform_erase_waiting)
        EraseStatus.NeedsReauth -> context.getString(R.string.platform_erase_reauth)
        EraseStatus.LocalWipeFailed -> context.getString(R.string.platform_erase_local_failed)
        is EraseStatus.Finished -> when {
            status.result.server == ServerErase.FAILED || status.result.account == AccountErase.FAILED ->
                context.getString(R.string.platform_erase_failed, privacyEmail)
            else -> context.getString(R.string.platform_erase_done)
        }
    }
}
