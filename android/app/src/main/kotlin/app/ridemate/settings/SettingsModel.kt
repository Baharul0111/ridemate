package app.ridemate.settings

import android.app.Activity
import app.ridemate.auth.AuthGateway
import app.ridemate.auth.SignInResult
import app.ridemate.config.KillSwitches
import app.ridemate.dev.DeveloperModeGate
import app.ridemate.dev.VersionTapOutcome
import app.ridemate.privacy.EraseStatus
import app.ridemate.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Erase dialog (K1 wording → working → result). */
sealed interface EraseDialog {
    data object Hidden : EraseDialog
    data object Confirm : EraseDialog
    data object Working : EraseDialog

    /** Finished, queued or needs a fresh sign-in; [status] picks the wording. */
    data class Result(val status: EraseStatus) : EraseDialog
}

data class SettingsState(
    val keepSearching: Boolean,
    val keepSearchingWarning: Boolean,
    val language: AppLanguage,
    val killSwitches: KillSwitches,
    val eraseDialog: EraseDialog,
) {
    /** The keep-searching toggle shows only while Remote Config `better_ride_enabled` allows better-ride offers. */
    val keepSearchingAvailable: Boolean get() = killSwitches.betterRideEnabled
}

/**
 * Settings (goal 24, 26, 16): privacy notice, My data, Erase, grievance email, language, keep-searching default,
 * kill-switch status. The erase itself runs in [appScope] so leaving the screen never interrupts it.
 */
class SettingsModel(
    private val store: SettingsStore,
    private val language: LanguageController,
    private val killSwitches: StateFlow<KillSwitches>,
    private val eraseStatus: StateFlow<EraseStatus>,
    private val erase: suspend () -> Unit,
    private val auth: AuthGateway,
    private val appScope: CoroutineScope,
    scope: CoroutineScope,
) {
    private val keepSearchingWarning = MutableStateFlow(false)
    private val eraseDialog = MutableStateFlow<EraseDialog>(EraseDialog.Hidden)
    private val languageState = MutableStateFlow(language.current())

    val state: StateFlow<SettingsState> = combine(
        store.keepSearchingDefault,
        keepSearchingWarning,
        languageState,
        killSwitches,
        combine(eraseDialog, eraseStatus) { dialog, status -> resolveDialog(dialog, status) },
    ) { keep, warning, lang, switches, dialog ->
        SettingsState(keep, warning, lang, switches, dialog)
    }.stateIn(scope, SharingStarted.Eagerly, snapshot(killSwitches.value))

    private fun snapshot(switches: KillSwitches) = SettingsState(
        keepSearching = store.keepSearchingDefault.value,
        keepSearchingWarning = keepSearchingWarning.value,
        language = languageState.value,
        killSwitches = switches,
        eraseDialog = resolveDialog(eraseDialog.value, eraseStatus.value),
    )

    /** While the dialog shows "working", the use case's status decides when it turns into a result. */
    private fun resolveDialog(dialog: EraseDialog, status: EraseStatus): EraseDialog = when {
        dialog != EraseDialog.Working -> dialog
        status == EraseStatus.Running || status == EraseStatus.Idle -> EraseDialog.Working
        else -> EraseDialog.Result(status)
    }

    /**
     * Turning keep-searching **on** first shows the fair-play warning; off applies at once. While `better_ride_enabled`
     * is off remotely it cannot be turned on (the toggle is hidden too).
     */
    fun setKeepSearching(on: Boolean) {
        if (!on) store.setKeepSearchingDefault(false) else if (killSwitches.value.betterRideEnabled) keepSearchingWarning.value = true
    }

    fun confirmKeepSearching() {
        keepSearchingWarning.value = false
        if (killSwitches.value.betterRideEnabled) store.setKeepSearchingDefault(true)
    }

    fun dismissKeepSearchingWarning() {
        keepSearchingWarning.value = false
    }

    /** Applies [lang]; true when the activity must be recreated (Android 10–12). */
    fun setLanguage(lang: AppLanguage): Boolean {
        val recreate = language.set(lang)
        languageState.value = lang
        return recreate
    }

    fun requestErase() {
        eraseDialog.value = EraseDialog.Confirm
    }

    fun confirmErase(): Job {
        eraseDialog.value = EraseDialog.Working
        return appScope.launch { runErase() }
    }

    /** "Sign in with Google" after [EraseStatus.NeedsReauth], then erase again to finish deleting the account. */
    fun reauthAndErase(activity: Activity): Job = appScope.launch {
        val result = try {
            auth.signInWithGoogle(activity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SignInResult.Failed
        }
        if (result is SignInResult.Success) {
            eraseDialog.value = EraseDialog.Working
            runErase()
        }
    }

    private suspend fun runErase() {
        try {
            erase()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // EraseUseCase reports failures through its status.
        }
        // A use case that finished without moving its status (fakes) still closes the "working" state.
        if (eraseDialog.value == EraseDialog.Working && eraseStatus.value == EraseStatus.Idle) {
            eraseDialog.value = EraseDialog.Result(EraseStatus.Idle)
        }
    }

    fun dismissEraseDialog() {
        eraseDialog.value = EraseDialog.Hidden
    }
}

/** About: version (seven taps → developer mode), update check, signing certificate, licences. */
class AboutModel(
    private val developerMode: DeveloperModeGate,
    private val checkUpdate: () -> UpdateState,
    private val refreshConfig: () -> Unit,
    initialUpdate: UpdateState,
) {
    private val _update = MutableStateFlow(initialUpdate)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    private val _checked = MutableStateFlow(false)

    /** Whether the user pressed "Check for updates" (so "You're up to date" can be shown). */
    val checked: StateFlow<Boolean> = _checked.asStateFlow()

    fun tapVersion(): VersionTapOutcome = developerMode.registerVersionTap()

    fun checkForUpdates() {
        refreshConfig()
        _update.value = checkUpdate()
        _checked.value = true
    }

    /** The update checker recomputed (config activation). */
    fun onUpdateState(state: UpdateState) {
        _update.value = state
    }
}
