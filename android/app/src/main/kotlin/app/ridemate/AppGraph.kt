package app.ridemate

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.ridemate.auth.AuthGateway
import app.ridemate.auth.AuthState
import app.ridemate.auth.DefaultAuthGateway
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
import app.ridemate.demo.AndroidDemoAssets
import app.ridemate.demo.DemoController
import app.ridemate.demo.DemoFareReader
import app.ridemate.messaging.FirebaseFcmTokenSource
import app.ridemate.messaging.OwnerAccess
import app.ridemate.messaging.OwnerAlertNotifier
import app.ridemate.messaging.OwnerMessaging
import app.ridemate.messaging.OwnerSignInWatcher
import app.ridemate.messaging.OwnerTokenRegistrar
import app.ridemate.net.ApiClient
import app.ridemate.net.OkHttpApiClient
import app.ridemate.ondc.FirestoreOndcTxnSource
import app.ridemate.ondc.OndcClient
import app.ridemate.outbox.OutboxDriver
import app.ridemate.outbox.OutboxRepository
import app.ridemate.outbox.OutboxWorkScheduler
import app.ridemate.outbox.WorkManagerOutboxScheduler
import app.ridemate.privacy.EraseUseCase
import app.ridemate.privacy.LocalDataWiper
import app.ridemate.privacy.MyDataProvider
import app.ridemate.privacy.SignInKind
import app.ridemate.update.UpdateChecker
import app.ridemate.util.BuildInfo
import app.ridemate.util.Clock
import app.ridemate.util.DefaultDispatcherProvider
import app.ridemate.util.DispatcherProvider
import app.ridemate.util.SessionActivity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
        OkHttpApiClient(
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
    val outboxDriver: OutboxDriver by lazy { OutboxDriver(outboxRepository, clock, sessionActivity, crashReporter) }
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
            scope = CoroutineScope(SupervisorJob() + dispatchers.main + CoroutineExceptionHandler { _, e -> crashReporter.recordNonFatal(e, CrashSite.OTHER) }),
            demoEnabled = configRepository.demoEnabled,
            cancelCards = { configRepository.resolve(ConfigKeys.CANCEL_CARDS, ::parseCancelCards)?.value },
        )
    }

    /** The ONDC buyer flow. Integration calls `ondcClient.bind { legId, event -> guardian.onProtocolEvent(legId, event) }`. */
    val ondcClient: OndcClient by lazy {
        OndcClient(
            api = apiClient,
            txns = FirestoreOndcTxnSource(),
            ondcEnabled = configRepository.ondcEnabled,
            clock = clock,
            scope = CoroutineScope(SupervisorJob() + dispatchers.main + CoroutineExceptionHandler { _, e -> crashReporter.recordNonFatal(e, CrashSite.OTHER) }),
        )
    }

    /** Whether the signed-in user is the owner (`owner_emails_sha256`; fails closed). */
    val ownerAccess: OwnerAccess by lazy {
        OwnerAccess(
            authState = { authGateway.state.value },
            ownerEmailHashes = { OwnerAccess.parseHashes(configRepository.string(ConfigKeys.OWNER_EMAILS_SHA256)) },
        )
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

        appScope.launch { ownerSignInWatcher.run(authGateway.state, configRepository.epoch) }

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
