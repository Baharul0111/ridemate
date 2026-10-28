package app.ridemate.ui

import app.ridemate.AppGraph
import app.ridemate.dev.DevExports
import app.ridemate.dev.DeveloperModeGate
import app.ridemate.dev.FirebaseOwnerPushTokens
import app.ridemate.dev.GoldenStore
import app.ridemate.dev.OwnerEmails
import app.ridemate.dev.OwnerPushTokens
import app.ridemate.dev.RecorderSink
import app.ridemate.dev.RoomRecorderSink
import app.ridemate.home.PreSessionGate
import app.ridemate.onboarding.AndroidPermissionChecker
import app.ridemate.onboarding.AndroidSelfTestNotifier
import app.ridemate.onboarding.DeviceProfile
import app.ridemate.onboarding.HealthCheck
import app.ridemate.onboarding.OnboardingController
import app.ridemate.onboarding.OnboardingStore
import app.ridemate.onboarding.PermissionChecker
import app.ridemate.onboarding.RideWatchSelfTest
import app.ridemate.onboarding.SignInReoffer
import app.ridemate.privacy.LocalDataWiper
import app.ridemate.settings.DefaultLanguageController
import app.ridemate.settings.LanguageController
import app.ridemate.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * The app shell's singletons (onboarding, home, settings, developer mode), reached as `graph.appShell`. Other areas
 * use:
 * - [recorderSink]: RideWatch hands every allowlisted post to it while `isRecording` (§4.19 a);
 * - [settingsStore]: `keepSearchingDefault`;
 * - [signInReoffer]: the one-time Google sign-in offer at the first "Read fares";
 * - [developerMode]: `isEnabled()` / `isRecording()`.
 */
class AppShell(private val graph: AppGraph) {
    private val context get() = graph.context

    val device: DeviceProfile by lazy { DeviceProfile.of(context) }
    val permissions: PermissionChecker by lazy { AndroidPermissionChecker(context) }

    val onboardingStore: OnboardingStore by lazy { OnboardingStore(SharedPrefsKeyValueStore(context, PREFS_ONBOARDING)) }
    val settingsStore: SettingsStore by lazy { SettingsStore(SharedPrefsKeyValueStore(context, SettingsStore.PREFS_NAME)) }
    val language: LanguageController by lazy { DefaultLanguageController.create(context, settingsStore) }

    /** A3's RideWatchStatus: has the listener seen RideMate's own self-test post? */
    val rideWatchSelfTest: RideWatchSelfTest get() = graph.featureBindings.rideWatchSelfTest

    val healthCheck: HealthCheck by lazy { HealthCheck(permissions, AndroidSelfTestNotifier(context), { rideWatchSelfTest }) }
    val signInReoffer: SignInReoffer by lazy { SignInReoffer(onboardingStore, graph.authGateway.state) }

    val developerMode: DeveloperModeGate by lazy {
        DeveloperModeGate(
            store = SharedPrefsKeyValueStore(context, PREFS_DEV),
            auth = graph.authGateway.state,
            ownerHashes = { OwnerEmails.hashesFrom(graph.configRepository) },
            clock = graph.clock,
        )
    }

    val recorderSink: RecorderSink by lazy {
        RoomRecorderSink({ graph.db.recorderPostDao() }, developerMode, graph.appScope, graph.dispatchers.io, graph.crashReporter)
    }

    val goldenStore: GoldenStore by lazy { GoldenStore(File(context.filesDir, GOLDEN_DIR)) }
    val exports: DevExports by lazy { DevExports(context) }
    val ownerPushTokens: OwnerPushTokens by lazy { FirebaseOwnerPushTokens() }

    /** A2's PreSessionCheck (permissions, installed apps, a RideWatch ping), as Home's Compare gate. */
    val preSessionGate: PreSessionGate get() = graph.featureBindings.preSessionGate

    fun onboardingController(scope: CoroutineScope): OnboardingController =
        OnboardingController(onboardingStore, permissions, device, graph.authGateway, healthCheck, scope)

    init {
        // Erase also forgets onboarding answers, the keep-searching choice, developer mode and every developer export.
        // The display language stays: it is how the phone shows text, not data about the user.
        graph.localWipers.add(LocalDataWiper { wipeLocal() })
    }

    fun wipeLocal() {
        onboardingStore.clear()
        settingsStore.setKeepSearchingDefault(false)
        developerMode.reset()
        goldenStore.clear()
        exports.clear()
    }

    companion object {
        const val PREFS_ONBOARDING = "ridemate_onboarding"
        const val PREFS_DEV = "ridemate_developer"
        const val GOLDEN_DIR = "golden"
    }
}
