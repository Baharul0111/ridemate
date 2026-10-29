package app.ridemate.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.ridemate.BuildConfig
import app.ridemate.R
import app.ridemate.dev.VersionTapOutcome
import app.ridemate.home.KillSwitchStatusList
import app.ridemate.home.UpdateBanner
import app.ridemate.onboarding.PrivacyNoticeContent
import app.ridemate.privacy.EraseStatus
import app.ridemate.privacy.PrivacyMessages
import app.ridemate.ui.ChipTone
import app.ridemate.ui.InfoCard
import app.ridemate.ui.KeyValueRow
import app.ridemate.ui.MinTouchTarget
import app.ridemate.ui.NavRow
import app.ridemate.ui.PrimaryButton
import app.ridemate.ui.RadioRow
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.ScreenScaffold
import app.ridemate.ui.SecondaryButton
import app.ridemate.ui.SectionHeader
import app.ridemate.ui.StatusChip
import app.ridemate.ui.ToggleRow
import app.ridemate.update.UpdateState

class SettingsActions(
    val back: () -> Unit = {},
    val privacyNotice: () -> Unit = {},
    val myData: () -> Unit = {},
    val requestErase: () -> Unit = {},
    val confirmErase: () -> Unit = {},
    val dismissErase: () -> Unit = {},
    val reauthErase: () -> Unit = {},
    val eraseFinished: () -> Unit = {},
    val contactPrivacy: () -> Unit = {},
    val setLanguage: (AppLanguage) -> Unit = {},
    val setKeepSearching: (Boolean) -> Unit = {},
    val confirmKeepSearching: () -> Unit = {},
    val dismissKeepSearching: () -> Unit = {},
    val about: () -> Unit = {},
    val reportProblem: () -> Unit = {},
)

@Composable
fun SettingsScreen(state: SettingsState, versionName: String, actions: SettingsActions) {
    ScreenScaffold(title = stringResource(R.string.set_title), onBack = actions.back) {
        SectionHeader(stringResource(R.string.set_section_privacy))
        NavRow(stringResource(R.string.set_privacy_notice), actions.privacyNotice, icon = Icons.Filled.Policy)
        NavRow(
            stringResource(R.string.set_my_data),
            actions.myData,
            summary = stringResource(R.string.set_my_data_summary),
            icon = Icons.Filled.Inventory2,
        )
        NavRow(
            stringResource(R.string.set_erase),
            actions.requestErase,
            summary = stringResource(R.string.set_erase_summary),
            icon = Icons.Filled.DeleteForever,
        )
        NavRow(
            stringResource(R.string.set_contact),
            actions.contactPrivacy,
            summary = stringResource(R.string.set_contact_summary, BuildConfig.PRIVACY_EMAIL),
            icon = Icons.Filled.Email,
        )

        SectionHeader(stringResource(R.string.set_section_language))
        LanguagePicker(state.language, actions.setLanguage)

        // Remote Config `better_ride_enabled` off: the whole opt-in is hidden (goal 22).
        if (state.keepSearchingAvailable) {
            SectionHeader(stringResource(R.string.set_section_rides))
            ToggleRow(
                title = stringResource(R.string.set_keep_searching),
                checked = state.keepSearching,
                onCheckedChange = actions.setKeepSearching,
                summary = stringResource(R.string.set_keep_searching_summary),
            )
        }

        SectionHeader(stringResource(R.string.set_section_status))
        Text(stringResource(R.string.set_status_body), style = MaterialTheme.typography.bodyMedium)
        KillSwitchStatusList(state.killSwitches)

        SectionHeader(stringResource(R.string.set_section_app))
        NavRow(
            stringResource(R.string.set_about),
            actions.about,
            summary = stringResource(R.string.set_about_summary, versionName),
            icon = Icons.Filled.Info,
        )
        NavRow(
            stringResource(R.string.set_report),
            actions.reportProblem,
            summary = stringResource(R.string.set_report_summary),
            icon = Icons.AutoMirrored.Filled.Help,
        )
        Spacer(Modifier.size(16.dp))
    }
    EraseDialogHost(state.eraseDialog, actions)
    if (state.keepSearchingWarning && state.keepSearchingAvailable) KeepSearchingWarning(actions.confirmKeepSearching, actions.dismissKeepSearching)
}

/** English / हिन्दी (and "phone's language"), each a radio row read with its own name. */
@Composable
fun LanguagePicker(selected: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    Column {
        RadioRow(stringResource(R.string.set_language_system), selected == AppLanguage.SYSTEM, { onSelect(AppLanguage.SYSTEM) })
        RadioRow(stringResource(R.string.set_language_english), selected == AppLanguage.ENGLISH, { onSelect(AppLanguage.ENGLISH) })
        RadioRow(stringResource(R.string.set_language_hindi), selected == AppLanguage.HINDI, { onSelect(AppLanguage.HINDI) })
    }
}

/** The fair-play warning shown before keep-searching can be turned on (goal 16, §4.8). */
@Composable
fun KeepSearchingWarning(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_keep_searching_warning_title)) },
        text = { Text(stringResource(R.string.set_keep_searching_warning)) },
        confirmButton = { TextButton(onConfirm, Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.set_keep_searching_confirm)) } },
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.set_cancel)) } },
    )
}

/** The Erase dialog: the exact K1 wording, then progress, then what happened and what to do next. */
@Composable
fun EraseDialogHost(dialog: EraseDialog, actions: SettingsActions) {
    val context = LocalContext.current
    when (dialog) {
        EraseDialog.Hidden -> Unit
        EraseDialog.Confirm -> AlertDialog(
            onDismissRequest = actions.dismissErase,
            title = { Text(stringResource(R.string.set_erase_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.set_erase_k1))
                    Text(stringResource(R.string.set_erase_irreversible), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(actions.confirmErase, Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.set_erase_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(actions.dismissErase, Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.set_cancel)) }
            },
        )
        EraseDialog.Working -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.set_erase_working)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.set_erase_working_body))
                }
            },
            confirmButton = {},
        )
        is EraseDialog.Result -> {
            val text = PrivacyMessages.eraseStatusText(context, dialog.status, BuildConfig.PRIVACY_EMAIL)
                ?: stringResource(R.string.platform_erase_done)
            val reauth = dialog.status == EraseStatus.NeedsReauth
            AlertDialog(
                onDismissRequest = actions.eraseFinished,
                title = { Text(stringResource(R.string.set_erase_result_title)) },
                text = { Text(text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
                confirmButton = {
                    if (reauth) {
                        TextButton(actions.reauthErase, Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.set_erase_reauth)) }
                    } else {
                        TextButton(actions.eraseFinished, Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.set_ok)) }
                    }
                },
                dismissButton = if (reauth) {
                    { TextButton(actions.eraseFinished, Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.set_later)) } }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
fun PrivacyNoticeScreen(onBack: () -> Unit, onContact: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.set_privacy_notice), onBack = onBack) {
        PrivacyNoticeContent()
        SecondaryButton(stringResource(R.string.set_contact_button), onContact, icon = Icons.Filled.Email)
        Spacer(Modifier.size(16.dp))
    }
}

/** My data: what RideMate holds on this phone (counts, sign-in, last session, settings), plus Erase. */
@Composable
fun MyDataScreen(lines: List<String>?, onBack: () -> Unit, onErase: () -> Unit, onContact: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.set_my_data), onBack = onBack) {
        Text(stringResource(R.string.set_my_data_intro), style = MaterialTheme.typography.bodyLarge)
        if (lines == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.set_loading))
            }
        } else {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp)) }
            }
        }
        Text(stringResource(R.string.set_my_data_server), style = MaterialTheme.typography.bodyMedium)
        PrimaryButton(stringResource(R.string.set_erase), onErase, icon = Icons.Filled.DeleteForever)
        SecondaryButton(stringResource(R.string.set_contact_button), onContact, icon = Icons.Filled.Email)
    }
}

class AboutActions(
    val back: () -> Unit = {},
    val tapVersion: () -> Unit = {},
    val checkUpdates: () -> Unit = {},
    val download: (String) -> Unit = {},
    val licences: () -> Unit = {},
    val sourceCode: () -> Unit = {},
    val developer: () -> Unit = {},
)

data class AboutUiState(
    val versionName: String,
    val versionCode: Int,
    val update: UpdateState,
    val checked: Boolean,
    val certificates: List<String>,
    val developerModeOn: Boolean,
    val tapOutcome: VersionTapOutcome?,
)

@Composable
fun AboutScreen(state: AboutUiState, actions: AboutActions) {
    ScreenScaffold(title = stringResource(R.string.set_about), onBack = actions.back) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(onClick = actions.tapVersion)
                .padding(vertical = 8.dp)
                .semantics(mergeDescendants = true) {},
        ) {
            Text(stringResource(R.string.set_version_label), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.set_version_value, state.versionName, state.versionCode), style = MaterialTheme.typography.bodyLarge)
        }
        state.tapOutcome?.let { outcome ->
            tapMessage(outcome)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }

        SectionHeader(stringResource(R.string.set_updates))
        UpdateBanner(state.update, actions.download)
        if (state.checked && !state.update.available && !state.update.belowMinSupported) {
            StatusChip(stringResource(R.string.set_up_to_date), ChipTone.GOOD)
        }
        SecondaryButton(stringResource(R.string.set_check_updates), actions.checkUpdates)

        SectionHeader(stringResource(R.string.set_certificate))
        Text(stringResource(R.string.set_certificate_body), style = MaterialTheme.typography.bodyMedium)
        if (state.certificates.isEmpty()) {
            Text(stringResource(R.string.set_certificate_unavailable), style = MaterialTheme.typography.bodyMedium)
        } else {
            SelectionContainer {
                Column { state.certificates.forEach { Text(it, style = RideMateTheme.mono) } }
            }
        }

        InfoCard(
            title = stringResource(R.string.built_with_spark),
            body = stringResource(R.string.set_built_with_body),
            tone = ChipTone.GOOD,
        )
        NavRow(stringResource(R.string.set_licences), actions.licences, icon = Icons.Filled.Gavel)
        NavRow(
            stringResource(R.string.set_source),
            actions.sourceCode,
            summary = stringResource(R.string.set_source_summary, BuildConfig.GITHUB_REPO),
            icon = Icons.Filled.Code,
        )
        if (state.developerModeOn) {
            NavRow(stringResource(R.string.set_developer), actions.developer, icon = Icons.Filled.DeveloperMode)
        }
        Spacer(Modifier.size(16.dp))
    }
}

@Composable
private fun tapMessage(outcome: VersionTapOutcome): String? = when (outcome) {
    is VersionTapOutcome.Counting -> if (outcome.remaining <= 4) {
        androidx.compose.ui.res.pluralStringResource(R.plurals.set_dev_taps_left, outcome.remaining, outcome.remaining)
    } else {
        null
    }
    VersionTapOutcome.Enabled, VersionTapOutcome.AlreadyEnabled -> stringResource(R.string.set_dev_on)
    VersionTapOutcome.SignInRequired -> stringResource(R.string.set_dev_sign_in)
    VersionTapOutcome.NotOwner -> stringResource(R.string.set_dev_not_owner)
}

/** Third-party software in RideMate and its licences (names are not translated). */
object Licences {
    val ENTRIES: List<Pair<Int, Int>> = listOf(
        R.string.lic_ridemate to R.string.lic_mit,
        R.string.lic_androidx to R.string.lic_apache,
        R.string.lic_kotlin to R.string.lic_apache,
        R.string.lic_okhttp to R.string.lic_apache,
        R.string.lic_re2j to R.string.lic_go,
        R.string.lic_firebase to R.string.lic_apache,
        R.string.lic_credentials to R.string.lic_apache,
        R.string.lic_mlkit to R.string.lic_mlkit_terms,
        R.string.lic_play_location to R.string.lic_play_terms,
        R.string.lic_spark to R.string.lic_spark_terms,
    )
}

@Composable
fun LicencesScreen(onBack: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.set_licences), onBack = onBack) {
        Text(stringResource(R.string.set_licences_intro), style = MaterialTheme.typography.bodyMedium)
        Licences.ENTRIES.forEach { (name, licence) -> KeyValueRow(stringResource(name), stringResource(licence)) }
    }
}
