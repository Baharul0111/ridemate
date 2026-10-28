package app.ridemate.session

import androidx.core.content.ContextCompat
import app.ridemate.AppGraph
import app.ridemate.launch.ProcessForeground
import app.ridemate.notify.CancelStepsSource
import app.ridemate.notify.GuardianNotifier
import app.ridemate.notify.GuardianPresenter
import app.ridemate.notify.GuardianText
import app.ridemate.privacy.LocalDataWiper
import app.ridemate.watch.ApiNotificationClassifier
import app.ridemate.watch.PiiTextMasker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Wires the ride guardian (watch / session / notify) from the [AppGraph]. One per process (`graph.guardian`).
 * Tests build [GuardianRuntime] directly with fakes; production uses this.
 */
class GuardianModule(private val graph: AppGraph) {
    private val context = graph.app

    /** The guardian's own thread: core's Guardian and RideWatchPipeline are confined to it. */
    private val guardianDispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ridemate-guardian").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    }

    val config: GuardianConfigSource by lazy {
        RemoteGuardianConfig(graph.configRepository) { context.resources.configuration.locales[0]?.language ?: "en" }
    }

    val cancelSteps: CancelStepsSource = CancelStepsSource { provider -> config.cancelSteps(provider) }

    /** A presenter in the current UI language (built per use so a language change applies at once). */
    fun presenter(): GuardianPresenter = GuardianPresenter(GuardianText(context), cancelSteps)

    val notifier: GuardianNotifier by lazy {
        GuardianNotifier(context, ::presenter) { provider, purpose -> graph.guardianBindings.opener.intentFor(provider, purpose) }
    }

    val keepSearching: KeepSearchingPreference by lazy {
        val settings = graph.appShell.settingsStore
        KeepSearchingPreference({ settings.keepSearchingDefault.value }, settings::setKeepSearchingDefault)
    }

    private val journal by lazy { UiJournalStore(context) }

    /** The same foreground test the launcher uses (Android 15 background-start rule). */
    private val foreground = ProcessForeground()

    val runtime: GuardianRuntime by lazy {
        GuardianRuntime(
            clock = graph.clock,
            guardianDispatcher = guardianDispatcher,
            ioDispatcher = graph.dispatchers.io,
            parentScope = graph.appScope,
            persistence = GuardianPersistence(graph.db, graph.guardianSnapshots, graph.outboxRepository, graph.storageHealth, journal),
            outboxDriver = graph.outboxDriver,
            config = config,
            classifierFactory = { activeSessionId ->
                ApiNotificationClassifier(
                    api = graph.apiClient,
                    authState = { graph.authGateway.state.value },
                    aiEnabled = { graph.configRepository.aiReaderEnabled.value },
                    activeSessionId = activeSessionId,
                )
            },
            masker = PiiTextMasker(),
            notifier = notifier,
            presenter = ::presenter,
            bindings = graph.guardianBindings,
            watchStatus = graph.rideWatchStatus,
            serviceStarter = ServiceStarter { RideSessionService.ensureRunning(context) },
            sessionActivity = graph.sessionActivity,
            keepSearchingPreference = keepSearching,
            appOpener = GuardianAppOpener(context, graph.guardianBindings, notifier, ::presenter) { foreground.isForeground },
            captureGate = ServiceCaptureGate(context),
            mainExecutor = ContextCompat.getMainExecutor(context),
            crash = graph.crashReporter,
        ).also { runtime ->
            graph.localWipers.add(
                LocalDataWiper {
                    runtime.wipe()
                    journal.clear()
                    keepSearching.clear()
                },
            )
        }
    }

    /** Process start: register the outbox handlers and restore an active session. */
    fun start() = runtime.start()
}
