package app.ridemate

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.ridemate.auth.AuthGateway
import app.ridemate.auth.AuthState
import app.ridemate.auth.DefaultAuthGateway
import app.ridemate.compare.ActiveComparison
import app.ridemate.compare.FareReadingModule
import app.ridemate.compare.RoomActiveComparison
import app.ridemate.config.ConfigKeys
import app.ridemate.config.ConfigRepository
import app.ridemate.core.config.SanityBandsConfig
import app.ridemate.core.config.parseCancelCards
import app.ridemate.core.config.parseFareParsers
import app.ridemate.core.config.parseSanityBands
import app.ridemate.crash.CrashKey
import app.ridemate.crash.CrashReporter
import app.ridemate.crash.CrashSite
import app.ridemate.data.GuardianSnapshotStore
import app.ridemate.data.PurgeWorker
import app.ridemate.data.RetentionPurger
import app.ridemate.data.RideMateDb
import app.ridemate.data.StorageHealth
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.model.Provider
import app.ridemate.bubble.TapIds
import app.ridemate.integration.CaptureDevTools
import app.ridemate.integration.ComparisonStarter
import app.ridemate.integration.GuardianCaptureHost
import app.ridemate.integration.GuardianCompareCommands
import app.ridemate.integration.GuardianDemoHost
import app.ridemate.integration.GuardianLegOpenedSink
import app.ridemate.integration.NetworkSearch
import app.ridemate.integration.OcrEngineDemoOcr
import app.ridemate.integration.OndcResume
import app.ridemate.integration.OndcTxnMemory
import app.ridemate.integration.PrecheckGate
import app.ridemate.integration.RideMateScreens
import app.ridemate.integration.RouteFareLauncher
import app.ridemate.integration.RouteLauncher
import app.ridemate.integration.ScreenCheck
import app.ridemate.integration.SessionBubble
import app.ridemate.integration.SessionBubbleDeps
import app.ridemate.notify.GuardianIntents
import app.ridemate.notify.SessionOp
import app.ridemate.session.CompareAgainHandler
import app.ridemate.session.FareReadRequester
import app.ridemate.session.OndcCommandSink
import app.ridemate.session.RideSessionService
import app.ridemate.launch.AndroidDropClipboard
import app.ridemate.launch.AndroidLaunchNotifier
import app.ridemate.launch.AppLauncher
import app.ridemate.launch.BubbleVisibility
import app.ridemate.launch.ContextActivityStarter
import app.ridemate.launch.InstalledApps
import app.ridemate.launch.PackageManagerApps
import app.ridemate.launch.ProcessForeground
import app.ridemate.launch.RemoteLaunchTemplates
import app.ridemate.demo.AndroidDemoAssets
import app.ridemate.demo.DemoController
import app.ridemate.demo.DemoFareReader
import app.ridemate.messaging.FirebaseFcmTokenSource
import app.ridemate.messaging.OwnerAccess
import app.ridemate.messaging.ServerOwnerStatus
import app.ridemate.messaging.OwnerAlertNotifier
import app.ridemate.messaging.OwnerMessaging
import app.ridemate.messaging.OwnerSignInWatcher
import app.ridemate.messaging.OwnerTokenRegistrar
import app.ridemate.net.ApiClient
import app.ridemate.net.OkHttpApiClient
import app.ridemate.ondc.OndcClient
import app.ridemate.outbox.OutboxDriver
import app.ridemate.outbox.OutboxRepository
import app.ridemate.outbox.OutboxWorkScheduler
import app.ridemate.outbox.WorkManagerOutboxScheduler
import app.ridemate.privacy.EraseUseCase
import app.ridemate.privacy.LocalDataWiper
import app.ridemate.privacy.MyDataProvider
import app.ridemate.privacy.SignInKind
import app.ridemate.precheck.AndroidPrecheckProbes
import app.ridemate.precheck.PreSessionCheck
import app.ridemate.trip.RecentPlacesStore
import app.ridemate.ui.AppShell
import app.ridemate.ui.FeatureBindings
import app.ridemate.ui.SharedPrefsKeyValueStore
import app.ridemate.session.GuardianModule
import app.ridemate.session.GuardianRuntime
import app.ridemate.session.SessionBindings
import app.ridemate.update.UpdateChecker
import app.ridemate.util.BuildInfo
import app.ridemate.util.Clock
import app.ridemate.util.DefaultDispatcherProvider
import app.ridemate.util.DispatcherProvider
import app.ridemate.util.SessionActivity
import app.ridemate.watch.RideWatchStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList

/**
 * RideMate's manual dependency container: one instance per process, created in [RideMateApp.onCreate] and reached
 * with `(context.applicationContext as RideMateApp).graph` (or [RideMateApp.graph]). Members are lazy singletons, so
 * nothing (Firebase, Room, OkHttp) is created before first use. Later areas add their own `by lazy` members here.
 *
 * Platform members:
 * - [db] Room database, [guardianSnapshots], [retentionPurger], [storageHealth]
 * - [authGateway] Google / anonymous sign-in and ID tokens
 * - [configRepository] Remote Config (kill switches, layered payloads, api_base_url)
 * - [apiClient] the server API (contracts §1)
 * - [outboxRepository] / [outboxDriver] ActionOutbox (register handlers with `outboxDriver.register`)
 * - [eraseUseCase], [myDataProvider], [localWipers] privacy
 * - [crashReporter] sanitized crash reporting
 * - [updateChecker] update banner state
 * - [sessionActivity] whether a ride session's foreground service runs (the session service sets it)
 * - [appLauncher], [preSessionCheck], [installedApps], [recentPlaces] trip input / launch / pre-session check
 * - [fareReading] bubble host, capture, OCR + masking, Read fares, Show me, the comparison list
 * - [guardian] / [guardianRuntime] RideWatch, the session service, notifications; [sessionBubble] the overlay bubble
 * - [demoController], [ondcClient], [ownerMessaging]; [appShell] onboarding, home, settings, developer mode
 *
 * Every cross-area port is bound here to its one real implementation (package `app.ridemate.integration`).
 */
class AppGraph(
    val app: Application,
    val seams: PlatformSeams = PlatformSeams.production(),
    val build: BuildInfo = BuildInfo.CURRENT,
    val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) {
    val context: Context get() = app
    val clock: Clock get() = seams.clock

    val crashReporter: CrashReporter by lazy { CrashReporter(seams.crashBackend) }

    /** Process-wide scope for platform work; failures are reported (sanitized), never crash the app. */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + dispatchers.default + CoroutineExceptionHandler { _, e -> crashReporter.recordNonFatal(e, CrashSite.OTHER) },
    )

    /** Main-thread scope for the parts confined to it (demo, ONDC, launcher calls from taps). */
    val mainScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + dispatchers.main + CoroutineExceptionHandler { _, e -> crashReporter.recordNonFatal(e, CrashSite.OTHER) },
    )

    val sessionActivity: SessionActivity = SessionActivity()
    val storageHealth: StorageHealth = StorageHealth()

    // ---- data ----
    val db: RideMateDb by lazy { seams.database(app) }
    val retentionPurger: RetentionPurger by lazy { RetentionPurger(db, clock) }
    val guardianSnapshots: GuardianSnapshotStore by lazy { GuardianSnapshotStore(db.guardianSnapshotDao(), clock, storageHealth) }

    // ---- auth / config / net ----
    val authGateway: AuthGateway by lazy { DefaultAuthGateway(seams.authBackend(), seams.googleCredentials(app), clock) }

    val configRepository: ConfigRepository by lazy {
        ConfigRepository(
            backend = seams.remoteConfigBackend(),
            bundledSource = seams.bundledConfig(app),
            lastGoodPrefs = app.getSharedPreferences(ConfigRepository.PREFS_NAME, Context.MODE_PRIVATE),
            scope = appScope,
            crash = crashReporter,
            minimumFetchIntervalSeconds = if (build.debug) DEBUG_MIN_FETCH_INTERVAL_S else ConfigRepository.DEFAULT_MIN_FETCH_INTERVAL_S,
        )
    }

    val httpClient: OkHttpClient by lazy { seams.httpClient() }

    val apiClient: ApiClient by lazy {
        seams.apiClient() ?: OkHttpApiClient(
            http = httpClient,
            baseUrl = { configRepository.apiBaseUrl() },
            tokens = authGateway,
            appVersionCode = build.versionCode,
            clock = clock,
        )
    }

    // ---- outbox ----
    val outboxRepository: OutboxRepository by lazy {
        OutboxRepository(db.outboxDao(), clock, seams.bootIds(app), storageHealth)
    }
    /** Register handlers with `outboxDriver.register(kinds) { … }`; network-bound ones with `register(kinds, OutboxDispatch.BACKGROUND) { … }`. */
    val outboxDriver: OutboxDriver by lazy {
        OutboxDriver(outboxRepository, clock, sessionActivity, crashReporter, backgroundScope = appScope)
    }
    val outboxScheduler: OutboxWorkScheduler by lazy { WorkManagerOutboxScheduler({ seams.workManager(app) }, clock) }

    // ---- privacy / update ----
    /** Extra local stores Erase wipes; areas add theirs (recent places, onboarding answers, exports, …). */
    val localWipers: MutableList<LocalDataWiper> = CopyOnWriteArrayList<LocalDataWiper>().apply {
        add(LocalDataWiper { guardianSnapshots.clearMemory() })
    }

    val eraseUseCase: EraseUseCase by lazy {
        EraseUseCase(
            wipeDatabase = { withContext(dispatchers.io) { db.clearAllTables() } },
            outbox = outboxRepository,
            api = apiClient,
            auth = authGateway,
            crash = crashReporter,
            extraWipers = { localWipers.toList() },
        ).also { it.registerWith(outboxDriver) }
    }

    val myDataProvider: MyDataProvider by lazy {
        MyDataProvider(db, { authGateway.state.value }, { configRepository.info() }, outboxRepository)
    }

    val updateChecker: UpdateChecker by lazy {
        UpdateChecker(
            build = build,
            latestVersion = { configRepository.latestVersion() },
            minSupportedVersion = { configRepository.minSupportedVersion() },
            configEpoch = configRepository.epoch,
            sessionActive = sessionActivity.active,
            scope = appScope,
        )
    }

    // ---- trip / launch / pre-session check (app.ridemate.trip, .launch, .precheck) ----
    @Volatile
    private var boundBubbleVisibility: BubbleVisibility? = null

    /** Whether the overlay bubble is visible (Android 15 background-start rule): the session service's bubble. */
    var bubbleVisibility: BubbleVisibility
        get() = boundBubbleVisibility ?: sessionBubble
        set(value) {
            boundBubbleVisibility = value
        }

    val installedApps: InstalledApps by lazy { PackageManagerApps(app) }

    val appLauncher: AppLauncher by lazy {
        AppLauncher(
            templates = RemoteLaunchTemplates(configRepository),
            apps = installedApps,
            starter = ContextActivityStarter(app),
            notifier = AndroidLaunchNotifier(app),
            clipboard = AndroidDropClipboard(app),
            bubble = object : BubbleVisibility {
                override val isShowing: Boolean get() = bubbleVisibility.isShowing
            },
            foreground = ProcessForeground(),
            legOpened = GuardianLegOpenedSink { guardianRuntime },
            clock = clock,
            onRecordFailure = { e -> crashReporter.recordNonFatal(e, CrashSite.OTHER) },
        )
    }

    val preSessionCheck: PreSessionCheck by lazy {
        PreSessionCheck(AndroidPrecheckProbes(app, installedApps), { rideWatchStatus }, clock)
    }

    /** Recent destinations (24 h, phone only); Erase wipes them. */
    val recentPlaces: RecentPlacesStore by lazy {
        RecentPlacesStore(app.getSharedPreferences(RecentPlacesStore.PREFS_NAME, Context.MODE_PRIVATE), clock)
    }

    init {
        localWipers.add(LocalDataWiper { recentPlaces.clear() })
        localWipers.add(LocalDataWiper { ondcTxnMemory.clear() })
        localWipers.add(LocalDataWiper { serverOwnerStatus.clear() })
    }

    // ---- fare reading (bubble, capture, OCR + masking, Read fares, comparison list) ----
    val fareReading: FareReadingModule by lazy {
        FareReadingModule(this).also { module ->
            module.sessionCommands = GuardianCompareCommands { guardianRuntime }
            module.appLauncher = RouteFareLauncher(
                launcher = { appLauncher },
                db = { db },
                installed = { installedApps },
                legIdFor = { guardianRuntime.legIdFor(it) },
                scope = appScope,
                onError = { crashReporter.recordNonFatal(it, CrashSite.OTHER) },
            )
            module.capture.foregroundHost = GuardianCaptureHost { guardianRuntime }
            // "Read fares" reads the app RideMate opened last (the guardian's notion, shared with "Booked here").
            module.activeComparison = ActiveComparison { RoomActiveComparison(db) { guardianRuntime.openedLegId }.current() }
        }
    }

    // ---- app shell (app.ridemate.ui / onboarding / home / settings / dev) ----
    /** Other areas' screens and services as the shell sees them. */
    val featureBindings: FeatureBindings by lazy {
        FeatureBindings(
            screens = RideMateScreens(this),
            preSessionGate = PrecheckGate(preSessionCheck, installedApps) { configRepository.ondcEnabled.value },
            rideWatchSelfTest = rideWatchStatus,
            captureTester = captureDevTools,
            goldenCaptures = captureDevTools,
        )
    }

    /** Developer mode's capture test and golden captures over the real capture pipeline. */
    val captureDevTools: CaptureDevTools by lazy {
        CaptureDevTools(
            capture = { fareReading.capture },
            ocr = { fareReading.ocrEngine },
            masker = { fareReading.masker },
            bubbleVisible = { sessionBubble.isVisible },
            holdService = { RideSessionService.holdForCapture(app) },
            clock = clock,
            cpu = dispatchers.default,
        )
    }

    /** Onboarding state, settings, language, developer mode, the Recorder sink and the self-test registry. */
    val appShell: AppShell by lazy { AppShell(this) }

    // ---- ride guardian: RideWatch, session service, notifications (packages watch / session / notify) ----
    /** Where the bubble, launcher, capture, ONDC and compare areas plug into the guardian. */
    val guardianBindings: SessionBindings by lazy {
        SessionBindings(app).also { b ->
            b.bubble = sessionBubble
            b.capture = screenCheck
            b.ondc = object : OndcCommandSink {
                override fun execute(command: GuardianEffect.OndcCommand) {
                    mainScope.launch { ondcClient.execute(command) }
                }

                override fun keepRide(legId: String) = ondcClient.keepRide(legId)
            }
            b.compareAgain = CompareAgainHandler { sessionId ->
                guardian.notifier.openRideMate(GuardianIntents.DESTINATION_COMPARE, sessionId)
            }
            b.fareReader = FareReadRequester { fareReading.coordinator.onReadFaresTap(TapIds.newTapId()) }
        }
    }

    /** The floating bubble the session service hosts (guardian card, fare reader status, Show me arrow). */
    val sessionBubble: SessionBubble by lazy {
        SessionBubble(
            deps = object : SessionBubbleDeps {
                override val status get() = guardianRuntime.status
                override val uiState get() = guardianRuntime.uiState
                override val pasteDrop get() = appLauncher.pasteDrop
                override fun presenter() = guardian.presenter()
                override fun showMeAvailable(provider: Provider): Boolean =
                    configRepository.boolean(ConfigKeys.POINT_SUPPORTED, true) && fareReading.showMe.isAvailable(provider)
                override fun readFares(tapId: String) {
                    fareReading.coordinator.onReadFaresTap(tapId)
                }
                override fun userAction(action: UserAction) = guardianRuntime.onUserAction(action)
                override fun sessionOp(op: SessionOp) = guardianRuntime.perform(op)
                override fun endCapture() = fareReading.endCapture()
                override fun copyDropAgain() {
                    appLauncher.copyDropAgain()
                }
            },
            main = dispatchers.main,
        ).also { bubble -> fareReading.bubbleHosts.host = bubble }
    }

    /** The guardian's user-tapped captures of a live ride app (Show me, cancel proof, Check screen). */
    val screenCheck: ScreenCheck by lazy {
        ScreenCheck(
            capture = { fareReading.capture },
            ocr = { fareReading.ocrEngine },
            readFares = { fareReading.readFares },
            showMe = { fareReading.showMe },
            commands = { guardianRuntime },
            sessionId = { guardianRuntime.status.value.sessionId },
            // The session's own distance: its legs are booked by now, so the fare reader's active leg is gone.
            tripKm = ::sessionTripKm,
            pointTarget = { provider -> cancelCards()?.cardFor(provider.packageName)?.pointTarget ?: DemoController.DEFAULT_POINT_TARGET },
            cancelledKeywords = { provider -> cancelCards()?.cardFor(provider.packageName)?.cancelledKeywords.orEmpty() },
            clock = clock,
            scope = appScope,
            main = dispatchers.main,
            onError = { crashReporter.recordNonFatal(it, CrashSite.CAPTURE) },
        )
    }

    private fun cancelCards() = configRepository.resolve(ConfigKeys.CANCEL_CARDS, ::parseCancelCards)?.value

    /**
     * The guardian session's straight-line trip distance for the gate's ₹/km band, whatever state its legs are in
     * (a Check screen reads a booked leg, which the fare reader's active-leg lookup no longer returns). Null only for
     * a typed place without coordinates, or when no session is stored.
     */
    suspend fun sessionTripKm(): Double? {
        val sessionId = guardianRuntime.status.value.sessionId ?: return null
        return db.sessionDao().get(sessionId)?.let { RoomActiveComparison.tripKmOf(it) }
    }

    /** The Compare tap after the trip screen's check: guardian session, ONDC search, first app opened. */
    val comparisonStarter: ComparisonStarter by lazy {
        ComparisonStarter(
            guardian = guardianRuntime,
            launcher = RouteLauncher { request -> appLauncher.openFromUserTap(request) },
            network = NetworkSearch { sessionId, legId, pickup, drop -> ondcClient.search(sessionId, legId, pickup, drop) },
            keepSearchingDefault = { appShell.settingsStore.keepSearchingDefault.value },
            scope = mainScope,
            onError = { crashReporter.recordNonFatal(it, CrashSite.OTHER) },
        )
    }
    val rideWatchStatus: RideWatchStatus by lazy { RideWatchStatus(app, clock) }
    val guardian: GuardianModule by lazy { GuardianModule(this) }

    /** The active session's guardian ([GuardianSessionApi] for demo/ONDC/UI, [SessionCommands] for the bubble). */
    val guardianRuntime: GuardianRuntime get() = guardian.runtime


    // ---- try the demo / ONDC / owner messaging (app.ridemate.demo, .ondc, .messaging) ----
    /**
     * Try the demo. Integration calls `demoController.bind(ocr, host)` with the capture area's OCR engine and the
     * session area's guardian session API; until then `start` answers NOT_READY.
     */
    val demoController: DemoController by lazy {
        val assets = AndroidDemoAssets(app.assets)
        DemoController(
            assets = assets,
            reader = DemoFareReader(
                assets = assets,
                api = apiClient,
                auth = authGateway,
                clock = clock,
                aiReaderEnabled = { configRepository.aiReaderEnabled.value },
                fareParsers = { configRepository.resolve(ConfigKeys.FARE_PARSERS, ::parseFareParsers)?.value },
                sanityBands = {
                    configRepository.resolve(ConfigKeys.SANITY_BANDS, ::parseSanityBands)?.value
                        ?: SanityBandsConfig.DEFAULT
                },
            ),
            api = apiClient,
            clock = clock,
            scope = mainScope,
            demoEnabled = configRepository.demoEnabled,
            cancelCards = { cancelCards() },
        ).also { controller ->
            controller.bind(
                ocr = OcrEngineDemoOcr({ fareReading.ocrEngine }, clock, dispatchers.default),
                host = GuardianDemoHost({ guardianRuntime }, { controller.script()?.trip }, clock),
            )
        }
    }

    /** The ONDC buyer flow. Integration calls `ondcClient.bind { legId, event -> guardian.onProtocolEvent(legId, event) }`. */
    val ondcClient: OndcClient by lazy {
        OndcClient(
            api = apiClient,
            txns = seams.ondcTxns(),
            ondcEnabled = configRepository.ondcEnabled,
            clock = clock,
            scope = mainScope,
        ).also { client ->
            client.bind { legId, event -> guardianRuntime.onProtocolEvent(legId, event) }
            // Remember the transaction (ids only) so a booked network ride survives a process restart.
            mainScope.launch {
                client.view.collect { view -> OndcResume.toRemember(view)?.let { (legId, txnId) -> ondcTxnMemory.remember(legId, txnId) } }
            }
        }
    }

    /** The network leg's ONDC transaction id, for re-attaching after a restart; Erase wipes it. */
    val ondcTxnMemory: OndcTxnMemory by lazy {
        OndcTxnMemory(app.getSharedPreferences(OndcTxnMemory.PREFS_NAME, Context.MODE_PRIVATE))
    }

    /** The server's answer to "is the signed-in Google account the owner?" (`/v1/config-check`; no list on the phone). */
    val serverOwnerStatus: ServerOwnerStatus by lazy {
        ServerOwnerStatus(SharedPrefsKeyValueStore(app, ServerOwnerStatus.PREFS_NAME), { authGateway.state.value }) {
            apiClient.configCheck()
        }
    }

    /** Whether the signed-in user is the owner, as the server confirmed it (fails closed). */
    val ownerAccess: OwnerAccess by lazy {
        OwnerAccess(authState = { authGateway.state.value }, isOwnerAccount = serverOwnerStatus::isOwner)
    }

    /** Owner alert pushes and the owner's FCM token registration ([app.ridemate.messaging.OwnerMessagingService]). */
    val ownerMessaging: OwnerMessaging by lazy {
        OwnerMessaging(
            registrar = OwnerTokenRegistrar(apiClient, ownerAccess, FirebaseFcmTokenSource),
            owner = ownerAccess,
            notify = OwnerAlertNotifier(app)::show,
        )
    }

    /** Registers the owner's FCM token when the owner signs in ([start] runs it; [ownerMessaging] is built only then). */
    val ownerSignInWatcher: OwnerSignInWatcher by lazy {
        OwnerSignInWatcher(isOwner = ownerAccess::isOwner, register = { ownerMessaging.registerCurrentToken() })
    }

    @Volatile
    private var started = false

    /**
     * Process start-up (called once by [RideMateApp.onCreate], on the main thread): crash collection in release
     * only, the sanitizing crash handler, Remote Config warm-up (fire-and-forget, again on every return to the
     * foreground via [foreground]), the ERASE_SERVER handler, the 24 h purge (now and periodically), outbox
     * reboot rebase and its WorkManager backstop.
     */
    @Synchronized
    fun start(foreground: LifecycleOwner?) {
        if (started) return
        started = true
        crashReporter.setCollectionEnabled(!build.debug)
        crashReporter.installUncaughtSanitizer()
        crashReporter.breadcrumb(CrashSite.APP_START)

        configRepository.start()
        foreground?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                configRepository.refreshInBackground()
            }
        })

        eraseUseCase // registers the ERASE_SERVER outbox handler
        guardian.start() // registers the guardian's outbox handlers and restores an active ride session
        appScope.launch {
            // A booked ONDC ride of the restored session: listen to its transaction again (status, fee, on_cancel).
            guardianRuntime.awaitIdle()
            OndcResume.resumeTarget(guardianRuntime.status.value, guardianRuntime.uiState.value, ondcTxnMemory)?.let { (legId, txnId) ->
                withContext(dispatchers.main) { ondcClient.resume(legId, txnId) }
            }
        }

        appScope.launch {
            // Ask the server whether this Google account is the owner at sign-in and whenever Remote Config activates
            // (the API URL may only arrive then); offline keeps the last answer.
            combine(authGateway.state, configRepository.epoch) { state, _ -> state }.collectLatest { state ->
                if (state is AuthState.Google) serverOwnerStatus.refresh()
            }
        }
        appScope.launch { ownerSignInWatcher.run(authGateway.state, serverOwnerStatus.confirmedOwnerUid) }

        appScope.launch {
            authGateway.state.collect { state ->
                val kind = when (state) {
                    AuthState.SignedOut -> SignInKind.SIGNED_OUT
                    is AuthState.Anonymous -> SignInKind.ANONYMOUS
                    is AuthState.Google -> SignInKind.GOOGLE
                }
                crashReporter.setKey(CrashKey.AUTH_KIND, kind)
            }
        }

        appScope.launch(dispatchers.io) {
            try {
                retentionPurger.purge()
            } catch (e: Exception) {
                crashReporter.recordNonFatal(e, CrashSite.PURGE)
            }
            try {
                recentPlaces.purgeExpired() // recent destinations follow ride data's 24 h retention
            } catch (e: Exception) {
                crashReporter.recordNonFatal(e, CrashSite.PURGE)
            }
            try {
                outboxRepository.rebaseAfterReboot()
            } catch (e: Exception) {
                crashReporter.recordNonFatal(e, CrashSite.OUTBOX)
            }
            outboxDriver.start(appScope, outboxScheduler)
        }

        try {
            PurgeWorker.schedule(seams.workManager(app))
        } catch (e: Exception) {
            crashReporter.recordNonFatal(e, CrashSite.PURGE)
        }
    }

    companion object {
        const val DEBUG_MIN_FETCH_INTERVAL_S: Long = 60
    }
}
