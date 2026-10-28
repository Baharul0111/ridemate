package app.ridemate

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.ridemate.auth.AuthGateway
import app.ridemate.auth.AuthState
import app.ridemate.auth.DefaultAuthGateway
import app.ridemate.config.ConfigRepository
import app.ridemate.crash.CrashKey
import app.ridemate.crash.CrashReporter
import app.ridemate.crash.CrashSite
import app.ridemate.data.GuardianSnapshotStore
import app.ridemate.data.PurgeWorker
import app.ridemate.data.RetentionPurger
import app.ridemate.data.RideMateDb
import app.ridemate.data.StorageHealth
import app.ridemate.launch.AndroidDropClipboard
import app.ridemate.launch.AndroidLaunchNotifier
import app.ridemate.launch.AppLauncher
import app.ridemate.launch.BubbleVisibility
import app.ridemate.launch.ContextActivityStarter
import app.ridemate.launch.InstalledApps
import app.ridemate.launch.LegOpenedSink
import app.ridemate.launch.PackageManagerApps
import app.ridemate.launch.ProcessForeground
import app.ridemate.launch.RemoteLaunchTemplates
import app.ridemate.launch.RoomLegOpenedSink
import app.ridemate.net.ApiClient
import app.ridemate.net.OkHttpApiClient
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
import app.ridemate.precheck.RideWatchStatus
import app.ridemate.trip.RecentPlacesStore
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
 * - [appLauncher], [preSessionCheck], [installedApps], [recentPlaces] trip input / launch / pre-session check; the
 *   bubble, RideWatch and guardian bind [bubbleVisibility], [rideWatchStatus] and [legOpenedSink]
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

    // ---- trip / launch / pre-session check (app.ridemate.trip, .launch, .precheck) ----
    /** Bound by the bubble area: whether the overlay bubble is visible (Android 15 background-start rule). */
    @Volatile
    var bubbleVisibility: BubbleVisibility = BubbleVisibility.Hidden

    /** Bound by RideWatch: answers the pre-session ping; null = read the enabled-listener list instead. */
    @Volatile
    var rideWatchStatus: RideWatchStatus? = null

    /** Bound by the guardian: records SELECTED → OPENED; null = AppLauncher moves the Room leg row itself. */
    @Volatile
    var legOpenedSink: LegOpenedSink? = null

    val installedApps: InstalledApps by lazy { PackageManagerApps(app) }

    val appLauncher: AppLauncher by lazy {
        val roomSink = RoomLegOpenedSink(db.legDao())
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
            legOpened = LegOpenedSink { legId, provider, at -> (legOpenedSink ?: roomSink).legOpened(legId, provider, at) },
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
