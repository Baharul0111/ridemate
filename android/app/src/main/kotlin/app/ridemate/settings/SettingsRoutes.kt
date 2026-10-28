package app.ridemate.settings

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ridemate.AppGraph
import app.ridemate.BuildConfig
import app.ridemate.R
import app.ridemate.dev.VersionTapOutcome
import app.ridemate.onboarding.AppPermission
import app.ridemate.privacy.EraseStatus
import app.ridemate.privacy.PrivacyMessages
import app.ridemate.ui.SystemIntents
import app.ridemate.ui.findActivity
import app.ridemate.ui.rememberScopedModel

/** Opens the user's email app to the privacy contact; says what to do when there is no email app. */
fun contactPrivacy(context: Context) {
    val sent = SystemIntents.sendEmail(context, BuildConfig.PRIVACY_EMAIL, context.getString(R.string.set_contact_subject), null)
    if (!sent) Toast.makeText(context, context.getString(R.string.set_no_email_app, BuildConfig.PRIVACY_EMAIL), Toast.LENGTH_LONG).show()
}

/** "Report a problem": metadata only (versions and enums), via the user's email app. */
fun reportProblem(context: Context, graph: AppGraph) {
    val shell = graph.appShell
    val input = ProblemReport.Input(
        build = graph.build,
        sdkInt = Build.VERSION.SDK_INT,
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        signIn = ProblemReport.signInKind(graph.authGateway.state.value),
        killSwitches = graph.configRepository.killSwitches.value,
        permissions = AppPermission.entries.associateWith { shell.permissions.isGranted(it) },
        language = shell.language.current(),
        onboardingCompleted = shell.onboardingStore.answers.value.completed,
        update = graph.updateChecker.state.value,
        developerMode = shell.developerMode.isEnabled(),
    )
    val body = ProblemReport.body(context.getString(R.string.set_report_intro), input)
    val sent = SystemIntents.sendEmail(context, BuildConfig.PRIVACY_EMAIL, ProblemReport.subject(context, graph.build), body)
    if (!sent) Toast.makeText(context, context.getString(R.string.set_no_email_app, BuildConfig.PRIVACY_EMAIL), Toast.LENGTH_LONG).show()
}

class SettingsNavigation(
    val back: () -> Unit,
    val privacyNotice: () -> Unit,
    val myData: () -> Unit,
    val about: () -> Unit,
    /** After an erase: onboarding starts again (its answers were erased too). */
    val afterErase: () -> Unit,
)

fun settingsModel(graph: AppGraph, scope: kotlinx.coroutines.CoroutineScope): SettingsModel = SettingsModel(
    store = graph.appShell.settingsStore,
    language = graph.appShell.language,
    killSwitches = graph.configRepository.killSwitches,
    eraseStatus = graph.eraseUseCase.status,
    erase = { graph.eraseUseCase.erase() },
    auth = graph.authGateway,
    appScope = graph.appScope,
    scope = scope,
)

@Composable
fun SettingsRoute(graph: AppGraph, nav: SettingsNavigation) {
    val model = rememberScopedModel("settings") { scope -> settingsModel(graph, scope) }
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsScreen(
        state = state,
        versionName = graph.build.versionName,
        actions = SettingsActions(
            back = nav.back,
            privacyNotice = nav.privacyNotice,
            myData = nav.myData,
            requestErase = model::requestErase,
            confirmErase = { model.confirmErase() },
            dismissErase = model::dismissEraseDialog,
            reauthErase = { context.findActivity()?.let { model.reauthAndErase(it) } },
            eraseFinished = {
                val wiped = !state.eraseDialog.localWipeFailed
                model.dismissEraseDialog()
                // A failed local wipe left everything in place: stay here so the user can free up storage and retry.
                if (wiped) nav.afterErase()
            },
            contactPrivacy = { contactPrivacy(context) },
            setLanguage = { lang -> if (model.setLanguage(lang)) context.findActivity()?.recreate() },
            setKeepSearching = model::setKeepSearching,
            confirmKeepSearching = model::confirmKeepSearching,
            dismissKeepSearching = model::dismissKeepSearchingWarning,
            about = nav.about,
            reportProblem = { reportProblem(context, graph) },
        ),
    )
}

@Composable
fun MyDataRoute(graph: AppGraph, onBack: () -> Unit, afterErase: () -> Unit) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) {
        lines = runCatching { PrivacyMessages.myDataLines(context, graph.myDataProvider.load()) }
            .getOrElse { listOf(context.getString(R.string.set_my_data_failed)) }
    }
    val model = rememberScopedModel("settings-mydata") { scope -> settingsModel(graph, scope) }
    val state by model.state.collectAsStateWithLifecycle()
    MyDataScreen(lines = lines, onBack = onBack, onErase = model::requestErase, onContact = { contactPrivacy(context) })
    EraseDialogHost(
        state.eraseDialog,
        SettingsActions(
            confirmErase = { model.confirmErase() },
            dismissErase = model::dismissEraseDialog,
            reauthErase = { context.findActivity()?.let { model.reauthAndErase(it) } },
            eraseFinished = {
                val wiped = !state.eraseDialog.localWipeFailed
                model.dismissEraseDialog()
                if (wiped) afterErase()
            },
        ),
    )
}

@Composable
fun AboutRoute(graph: AppGraph, onBack: () -> Unit, onLicences: () -> Unit, onDeveloper: () -> Unit) {
    val shell = graph.appShell
    val context = LocalContext.current
    val model = rememberScopedModel("about") {
        AboutModel(
            developerMode = shell.developerMode,
            checkUpdate = { graph.updateChecker.check() },
            refreshConfig = { graph.configRepository.refreshInBackground() },
            initialUpdate = graph.updateChecker.state.value,
        )
    }
    val liveUpdate by graph.updateChecker.state.collectAsStateWithLifecycle()
    LaunchedEffect(liveUpdate) { model.onUpdateState(liveUpdate) }
    val update by model.update.collectAsStateWithLifecycle()
    val checked by model.checked.collectAsStateWithLifecycle()
    val devOn by shell.developerMode.enabled.collectAsStateWithLifecycle(initialValue = shell.developerMode.isEnabled())
    val certificates = remember { SigningCertificate.sha256(context) }
    var outcome by rememberSaveable(stateSaver = TapOutcomeSaver) { mutableStateOf<VersionTapOutcome?>(null) }
    AboutScreen(
        state = AboutUiState(graph.build.versionName, graph.build.versionCode, update, checked, certificates, devOn, outcome),
        actions = AboutActions(
            back = onBack,
            tapVersion = {
                val result = model.tapVersion()
                outcome = result
                if (result == VersionTapOutcome.Enabled || result == VersionTapOutcome.AlreadyEnabled) onDeveloper()
            },
            checkUpdates = model::checkForUpdates,
            download = { SystemIntents.openHttps(context, it) },
            licences = onLicences,
            sourceCode = { SystemIntents.openHttps(context, "https://github.com/${BuildConfig.GITHUB_REPO}") },
            developer = onDeveloper,
        ),
    )
}

/** Tap outcomes are transient UI hints; only the final ones survive rotation. */
private val TapOutcomeSaver = androidx.compose.runtime.saveable.Saver<VersionTapOutcome?, String>(
    save = { outcome ->
        when (outcome) {
            VersionTapOutcome.Enabled -> "enabled"
            VersionTapOutcome.AlreadyEnabled -> "already"
            VersionTapOutcome.SignInRequired -> "sign_in"
            VersionTapOutcome.NotOwner -> "not_owner"
            else -> ""
        }
    },
    restore = { saved ->
        when (saved) {
            "enabled" -> VersionTapOutcome.Enabled
            "already" -> VersionTapOutcome.AlreadyEnabled
            "sign_in" -> VersionTapOutcome.SignInRequired
            "not_owner" -> VersionTapOutcome.NotOwner
            else -> null
        }
    },
)

/** The erase dialog shows the outcome "the data on this phone could not be wiped". */
private val EraseDialog.localWipeFailed: Boolean
    get() = (this as? EraseDialog.Result)?.status == EraseStatus.LocalWipeFailed
