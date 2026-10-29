package app.ridemate.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
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
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.components.IconWell
import app.ridemate.ui.components.InfoSheet
import app.ridemate.ui.components.InlineBanner
import app.ridemate.ui.components.QuietButton
import app.ridemate.ui.components.RmCard
import app.ridemate.ui.components.RmIconButton
import app.ridemate.ui.components.RmListDivider
import app.ridemate.ui.components.RmListGroup
import app.ridemate.ui.components.RmListRow
import app.ridemate.ui.components.RmPill
import app.ridemate.ui.components.RmRadioRow
import app.ridemate.ui.components.RmScaffold
import app.ridemate.ui.components.RmSectionTitle
import app.ridemate.ui.components.RmToggleRow
import app.ridemate.ui.components.RmTone
import app.ridemate.ui.components.WhyButton
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

/** A section title with an ⓘ that opens the section's explanation (long text never sits on the main screen). */
@Composable
fun SectionTitleWithWhy(title: String, whyDescription: String, onWhy: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RmSectionTitle(title, Modifier.weight(1f))
        WhyButton(onClick = onWhy, contentDescription = whyDescription)
    }
}

/** Settings: grouped icon rows with one- or two-word titles; explanations and legal text live in sheets. */
@Composable
fun SettingsScreen(state: SettingsState, versionName: String, actions: SettingsActions) {
    var keepInfo by rememberSaveable { mutableStateOf(false) }
    var statusInfo by rememberSaveable { mutableStateOf(false) }
    RmScaffold(title = stringResource(R.string.set_title), onBack = actions.back) {
        RmSectionTitle(stringResource(R.string.set_section_privacy))
        RmListGroup {
            RmListRow(stringResource(R.string.set_privacy_notice), icon = Icons.Rounded.Policy, iconTone = RmTone.BRAND, onClick = actions.privacyNotice)
            RmListDivider()
            RmListRow(stringResource(R.string.set_my_data), icon = Icons.Rounded.Inventory2, iconTone = RmTone.BRAND, onClick = actions.myData)
            RmListDivider()
            RmListRow(
                stringResource(R.string.set_contact),
                icon = Icons.Rounded.Email,
                iconTone = RmTone.BRAND,
                summary = stringResource(R.string.set_contact_summary, BuildConfig.PRIVACY_EMAIL),
                onClick = actions.contactPrivacy,
            )
            RmListDivider()
            RmListRow(
                stringResource(R.string.set_erase),
                icon = Icons.Rounded.DeleteForever,
                iconTone = RmTone.DANGER,
                destructive = true,
                onClick = actions.requestErase,
            )
        }

        RmSectionTitle(stringResource(R.string.set_section_language))
        LanguagePicker(state.language, actions.setLanguage)

        // Remote Config `better_ride_enabled` off: the whole opt-in is hidden (goal 22).
        if (state.keepSearchingAvailable) {
            SectionTitleWithWhy(stringResource(R.string.set_section_rides), stringResource(R.string.set_keep_searching_why)) { keepInfo = true }
            RmListGroup {
                RmToggleRow(
                    title = stringResource(R.string.set_keep_searching),
                    checked = state.keepSearching,
                    onCheckedChange = actions.setKeepSearching,
                    icon = Icons.Rounded.TravelExplore,
                    summary = stringResource(R.string.set_keep_searching_summary),
                )
            }
        }

        SectionTitleWithWhy(stringResource(R.string.set_section_status), stringResource(R.string.set_status_why)) { statusInfo = true }
        KillSwitchStatusList(state.killSwitches)

        RmSectionTitle(stringResource(R.string.set_section_app))
        RmListGroup {
            RmListRow(
                stringResource(R.string.set_about),
                icon = Icons.Rounded.Info,
                iconTone = RmTone.BRAND,
                summary = stringResource(R.string.set_about_summary, versionName),
                onClick = actions.about,
            )
            RmListDivider()
            RmListRow(
                stringResource(R.string.set_report),
                icon = Icons.AutoMirrored.Rounded.HelpOutline,
                summary = stringResource(R.string.set_report_summary),
                onClick = actions.reportProblem,
            )
        }
        Spacer(Modifier.size(16.dp))
    }
    EraseDialogHost(state.eraseDialog, actions)
    if (state.keepSearchingWarning && state.keepSearchingAvailable) KeepSearchingWarning(actions.confirmKeepSearching, actions.dismissKeepSearching)
    if (keepInfo && state.keepSearchingAvailable) {
        InfoSheet(
            onDismiss = { keepInfo = false },
            title = stringResource(R.string.set_keep_searching),
            body = stringResource(R.string.set_keep_searching_info),
            icon = Icons.Rounded.TravelExplore,
        ) {
            Text(stringResource(R.string.set_keep_searching_warning_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.set_keep_searching_warning), style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (statusInfo) {
        InfoSheet(onDismiss = { statusInfo = false }, title = stringResource(R.string.set_section_status), body = stringResource(R.string.set_status_body))
    }
}

/** English / हिन्दी (and "phone's language"), each a radio row read with its own name. */
@Composable
fun LanguagePicker(selected: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    RmListGroup {
        RmRadioRow(stringResource(R.string.set_language_system), selected == AppLanguage.SYSTEM, { onSelect(AppLanguage.SYSTEM) }, icon = Icons.Rounded.Language)
        RmListDivider()
        RmRadioRow(stringResource(R.string.set_language_english), selected == AppLanguage.ENGLISH, { onSelect(AppLanguage.ENGLISH) })
        RmListDivider()
        RmRadioRow(stringResource(R.string.set_language_hindi), selected == AppLanguage.HINDI, { onSelect(AppLanguage.HINDI) })
    }
}

/** The fair-play warning shown before keep-searching can be turned on (goal 16, §4.8), verbatim, in a sheet. */
@Composable
fun KeepSearchingWarning(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    InfoSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.set_keep_searching_warning_title),
        body = stringResource(R.string.set_keep_searching_warning),
        icon = Icons.Rounded.Warning,
        tone = RmTone.WARN,
        primaryLabel = stringResource(R.string.set_keep_searching_confirm),
        onPrimary = onConfirm,
        secondaryLabel = stringResource(R.string.set_cancel),
        onSecondary = onDismiss,
    )
}

/** The Erase sheet: the exact K1 wording, then progress, then what happened and what to do next. */
@Composable
fun EraseDialogHost(dialog: EraseDialog, actions: SettingsActions) {
    val context = LocalContext.current
    when (dialog) {
        EraseDialog.Hidden -> Unit
        EraseDialog.Confirm -> InfoSheet(
            onDismiss = actions.dismissErase,
            title = stringResource(R.string.set_erase_confirm_title),
            body = stringResource(R.string.set_erase_k1),
            icon = Icons.Rounded.DeleteForever,
            tone = RmTone.DANGER,
            primaryLabel = stringResource(R.string.set_erase_confirm),
            onPrimary = actions.confirmErase,
            primaryDestructive = true,
            secondaryLabel = stringResource(R.string.set_cancel),
            onSecondary = actions.dismissErase,
        ) {
            Text(stringResource(R.string.set_erase_irreversible), style = MaterialTheme.typography.bodyMedium, color = RideMateTheme.colors.muted)
        }
        // Not dismissable while erasing: a dialog window, so back and outside taps can't hide it half-way.
        EraseDialog.Working -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            icon = { CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp) },
            title = { Text(stringResource(R.string.set_erase_working)) },
            text = {
                Text(
                    stringResource(R.string.set_erase_working_body),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            },
            confirmButton = {},
        )
        is EraseDialog.Result -> {
            val text = PrivacyMessages.eraseStatusText(context, dialog.status, BuildConfig.PRIVACY_EMAIL)
                ?: stringResource(R.string.platform_erase_done)
            val reauth = dialog.status == EraseStatus.NeedsReauth
            InfoSheet(
                onDismiss = actions.eraseFinished,
                title = stringResource(R.string.set_erase_result_title),
                icon = Icons.Rounded.Info,
                tone = if (reauth) RmTone.WARN else RmTone.BRAND,
                primaryLabel = stringResource(if (reauth) R.string.set_erase_reauth else R.string.set_ok),
                onPrimary = if (reauth) actions.reauthErase else actions.eraseFinished,
                secondaryLabel = if (reauth) stringResource(R.string.set_later) else null,
                onSecondary = if (reauth) actions.eraseFinished else null,
            ) {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

@Composable
fun PrivacyNoticeScreen(onBack: () -> Unit, onContact: () -> Unit) {
    RmScaffold(title = stringResource(R.string.set_privacy_notice), onBack = onBack) {
        PrivacyNoticeContent()
        QuietButton(stringResource(R.string.set_contact_button), onContact, icon = Icons.Rounded.Email, fillWidth = true)
        Spacer(Modifier.size(16.dp))
    }
}

/** My data: what RideMate holds on this phone (counts, sign-in, last session, settings), plus Erase. */
@Composable
fun MyDataScreen(lines: List<String>?, onBack: () -> Unit, onErase: () -> Unit, onContact: () -> Unit) {
    var info by rememberSaveable { mutableStateOf(false) }
    RmScaffold(title = stringResource(R.string.set_my_data), onBack = onBack) {
        SectionTitleWithWhy(stringResource(R.string.set_my_data_on_phone), stringResource(R.string.set_my_data_why)) { info = true }
        if (lines == null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.set_loading))
            }
        } else {
            RmListGroup(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                lines.forEachIndexed { i, line ->
                    if (i > 0) RmListDivider()
                    RmListRow(line, icon = Icons.Rounded.Storage)
                }
            }
        }
        RmListGroup {
            RmListRow(stringResource(R.string.set_contact_button), icon = Icons.Rounded.Email, iconTone = RmTone.BRAND, onClick = onContact)
            RmListDivider()
            RmListRow(
                stringResource(R.string.set_erase),
                icon = Icons.Rounded.DeleteForever,
                iconTone = RmTone.DANGER,
                destructive = true,
                onClick = onErase,
            )
        }
    }
    if (info) {
        InfoSheet(onDismiss = { info = false }, title = stringResource(R.string.set_my_data), body = stringResource(R.string.set_my_data_intro)) {
            Text(stringResource(R.string.set_my_data_server), style = MaterialTheme.typography.bodyLarge)
        }
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
    var builtInfo by rememberSaveable { mutableStateOf(false) }
    var certInfo by rememberSaveable { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    RmScaffold(title = stringResource(R.string.set_about), onBack = actions.back) {
        // The version card: seven taps open developer mode for the owner.
        RmCard(onClick = actions.tapVersion) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconWell(Icons.Rounded.Info, tone = RmTone.BRAND)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.set_version_label), style = MaterialTheme.typography.labelMedium, color = RideMateTheme.colors.muted)
                    Text(stringResource(R.string.set_version_value, state.versionName, state.versionCode), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        state.tapOutcome?.let { outcome ->
            tapMessage(outcome)?.let {
                InlineBanner(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, tone = tapTone(outcome))
            }
        }

        // Built with Muse Spark Open: the one-line badge; what it means sits behind the ⓘ.
        RmCard(tone = RmTone.GOOD) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconWell(Icons.Rounded.AutoAwesome, tone = RmTone.GOOD)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.built_with_spark), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                WhyButton(onClick = { builtInfo = true }, contentDescription = stringResource(R.string.set_built_with_why))
            }
        }

        RmSectionTitle(stringResource(R.string.set_updates))
        UpdateBanner(state.update, actions.download)
        if (state.checked && !state.update.available && !state.update.belowMinSupported) {
            RmPill(stringResource(R.string.set_up_to_date), tone = RmTone.GOOD)
        }
        QuietButton(stringResource(R.string.set_check_updates), actions.checkUpdates, icon = Icons.Rounded.Refresh, fillWidth = true)

        SectionTitleWithWhy(stringResource(R.string.set_certificate), stringResource(R.string.set_certificate_why)) { certInfo = true }
        if (state.certificates.isEmpty()) {
            InlineBanner(stringResource(R.string.set_certificate_unavailable), tone = RmTone.WARN)
        } else {
            state.certificates.forEach { fingerprint ->
                RmCard(padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) { Text(fingerprint, style = RideMateTheme.mono) }
                        RmIconButton(
                            icon = Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.set_certificate_copy),
                            onClick = { clipboard.setText(AnnotatedString(fingerprint)) },
                        )
                    }
                }
            }
        }

        RmListGroup {
            RmListRow(stringResource(R.string.set_licences), icon = Icons.Rounded.Gavel, onClick = actions.licences)
            RmListDivider()
            RmListRow(
                stringResource(R.string.set_source),
                icon = Icons.Rounded.Code,
                summary = stringResource(R.string.set_source_summary, BuildConfig.GITHUB_REPO),
                onClick = actions.sourceCode,
            )
            if (state.developerModeOn) {
                RmListDivider()
                RmListRow(stringResource(R.string.set_developer), icon = Icons.Rounded.DeveloperMode, onClick = actions.developer)
            }
        }
        Spacer(Modifier.size(16.dp))
    }
    if (builtInfo) {
        InfoSheet(
            onDismiss = { builtInfo = false },
            title = stringResource(R.string.built_with_spark),
            body = stringResource(R.string.set_built_with_body),
            icon = Icons.Rounded.AutoAwesome,
            tone = RmTone.GOOD,
        )
    }
    if (certInfo) {
        InfoSheet(onDismiss = { certInfo = false }, title = stringResource(R.string.set_certificate), body = stringResource(R.string.set_certificate_body))
    }
}

private fun tapTone(outcome: VersionTapOutcome): RmTone = when (outcome) {
    is VersionTapOutcome.Counting -> RmTone.NEUTRAL
    VersionTapOutcome.Enabled, VersionTapOutcome.AlreadyEnabled -> RmTone.GOOD
    VersionTapOutcome.SignInRequired, VersionTapOutcome.NotOwner -> RmTone.WARN
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
    RmScaffold(title = stringResource(R.string.set_licences), onBack = onBack) {
        RmListGroup {
            Licences.ENTRIES.forEachIndexed { i, (name, licence) ->
                if (i > 0) RmListDivider()
                RmListRow(stringResource(name), icon = Icons.Rounded.Gavel, summary = stringResource(licence))
            }
        }
    }
}
