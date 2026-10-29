package app.ridemate.session

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import app.ridemate.core.config.CancelCardsConfig
import app.ridemate.core.config.CancelStep
import app.ridemate.core.config.NotificationTemplatesConfig
import app.ridemate.core.config.parseCancelCards
import app.ridemate.core.guardian.FeeRules
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Place
import app.ridemate.core.model.Provider
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.watch.NotificationClassifier
import app.ridemate.core.watch.PostRemoval
import app.ridemate.core.watch.PostSnapshot
import app.ridemate.core.watch.PostSource
import app.ridemate.core.watch.RemovalReason
import app.ridemate.core.watch.RemovalSource
import app.ridemate.crash.CrashReporter
import app.ridemate.data.GuardianSnapshotStore
import app.ridemate.data.RideMateDb
import app.ridemate.data.StorageHealth
import app.ridemate.notify.CancelStepsSource
import app.ridemate.notify.GuardianNotifier
import app.ridemate.notify.GuardianPresenter
import app.ridemate.notify.GuardianText
import app.ridemate.notify.GuardianUiState
import app.ridemate.outbox.OutboxDriver
import app.ridemate.outbox.OutboxRepository
import app.ridemate.testing.FakeBootIds
import app.ridemate.testing.FakeClock
import app.ridemate.testing.FakeCrashBackend
import app.ridemate.testing.TestSeams
import app.ridemate.util.SessionActivity
import app.ridemate.watch.PiiTextMasker
import app.ridemate.watch.RideWatchStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** The bundled Remote Config defaults (assets/config), as the guardian sees them before any remote value. */
class AssetGuardianConfig(private val appContext: Context) : GuardianConfigSource {
    private fun asset(name: String): String = appContext.assets.open("config/$name").bufferedReader().use { it.readText() }

    val templates: NotificationTemplatesConfig = NotificationTemplatesConfig.parse(asset("notification_templates.json"))
    val fees: FeeRules = FeeRules.parse(asset("fee_rules.json"))
    val cards: CancelCardsConfig = parseCancelCards(asset("cancel_cards.json"))

    override fun templates(): NotificationTemplatesConfig = templates
    override fun feeRules(): FeeRules = fees
    override fun cancelSteps(provider: Provider): List<CancelStep> = cards.cardFor(provider.packageName)?.stepsFor("en").orEmpty()

    /** Remote Config `better_ride_enabled`, flipped by tests. */
    val betterRide = MutableStateFlow(true)
    override val betterRideEnabled: StateFlow<Boolean> get() = betterRide

    companion object {
        /** The bundled payloads keyed by Remote Config key, for [app.ridemate.testing.TestSeams.bundled]. */
        fun bundled(context: Context): Map<String, String> = listOf("notification_templates", "fee_rules", "cancel_cards")
            .associateWith { key -> context.assets.open("config/$key.json").bufferedReader().use { it.readText() } }
    }
}

/**
 * An executor the test drives by hand ([runQueued], [awaitQueued]) until [runFreely] hands it a thread of its own, so a
 * test can place a call exactly between two steps of the guardian's own work.
 */
class SteppedExecutor : Executor, AutoCloseable {
    private val queue = LinkedBlockingQueue<Runnable>()

    @Volatile
    private var worker: Thread? = null

    override fun execute(command: Runnable) {
        queue.put(command)
    }

    /** Runs exactly the tasks queued now (not those they queue in turn). */
    fun runQueued() = repeat(queue.size) { queue.poll()?.run() }

    /** Waits until some other thread queues a task (a continuation coming back from storage). */
    fun awaitQueued(timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (queue.isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "nothing was queued" }
            Thread.sleep(2)
        }
    }

    fun runFreely() {
        worker = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val task = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    break
                }
                task.run()
            }
        }.apply {
            isDaemon = true
            name = "stepped-guardian"
            start()
        }
    }

    override fun close() {
        worker?.interrupt()
    }
}

/** A bubble that records what the guardian asked of it. */
class FakeBubble(override var isVisible: Boolean = false) : GuardianBubble {
    val pulses = AtomicInteger()
    val cards = CopyOnWriteArrayList<GuardianEffect.CancelCard>()
    val clears = AtomicInteger()
    var attached: Context? = null

    override fun attach(context: Context) {
        attached = context
    }

    override fun detach() {
        attached = null
    }

    override fun pulse() {
        pulses.incrementAndGet()
    }

    override fun showCancelCard(card: GuardianEffect.CancelCard) {
        cards += card
    }

    override fun clearCancelCard() {
        clears.incrementAndGet()
    }
}

class FakeCaptureGate : CaptureGate {
    var capturing = false
    override fun begin(): Boolean {
        capturing = true
        return true
    }

    override fun end() {
        capturing = false
    }
}

/**
 * A [GuardianRuntime] wired like production but with fakes: in-memory Room, a manual clock, the bundled config,
 * Robolectric notifications, a recording bubble and service starter. [kill] simulates process death; a second rig
 * over the same [db] (and clock) is the restarted process.
 */
class GuardianTestRig(
    val context: Context = ApplicationProvider.getApplicationContext(),
    val db: RideMateDb = TestSeams.inMemoryDb(context),
    val clock: FakeClock = FakeClock(),
    val bootIds: FakeBootIds = FakeBootIds(),
    classifier: NotificationClassifier? = null,
    /** The guardian's thread. Tests that need to interleave calls with the restore pass a [SteppedExecutor]. */
    private val executor: Executor = Executors.newSingleThreadExecutor { Thread(it, "test-guardian").apply { isDaemon = true } },
) {
    val storage = StorageHealth()
    val crashBackend = FakeCrashBackend()
    val crash = CrashReporter { crashBackend }
    val sessionActivity = SessionActivity()
    val outboxRepository = OutboxRepository(db.outboxDao(), clock, bootIds, storage)
    val outboxDriver = OutboxDriver(outboxRepository, clock, sessionActivity, crash)
    val config = AssetGuardianConfig(context)
    val bubble = FakeBubble()
    val bindings = SessionBindings(context).also { it.bubble = bubble }
    val watchStatus = RideWatchStatus(context, clock)
    val serviceStarts = AtomicInteger()
    var serviceStartResult = true

    @Volatile
    var appInForeground = false
    val captureGate = FakeCaptureGate()
    val journal = UiJournalStore(context)
    val keepSearching = KeepSearchingPreference.inMemory()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun presenter(): GuardianPresenter = GuardianPresenter(GuardianText(context), CancelStepsSource { config.cancelSteps(it) })

    val notifier = GuardianNotifier(context, ::presenter) { provider, purpose -> bindings.opener.intentFor(provider, purpose) }

    val runtime: GuardianRuntime = GuardianRuntime(
        clock = clock,
        guardianDispatcher = executor.asCoroutineDispatcher(),
        ioDispatcher = Dispatchers.IO,
        parentScope = scope,
        persistence = GuardianPersistence(db, GuardianSnapshotStore(db.guardianSnapshotDao(), clock, storage), outboxRepository, storage, journal),
        outboxDriver = outboxDriver,
        config = config,
        classifierFactory = { classifier },
        masker = PiiTextMasker(),
        notifier = notifier,
        presenter = ::presenter,
        bindings = bindings,
        watchStatus = watchStatus,
        serviceStarter = ServiceStarter {
            serviceStarts.incrementAndGet()
            serviceStartResult
        },
        sessionActivity = sessionActivity,
        keepSearchingPreference = keepSearching,
        appOpener = GuardianAppOpener(context, bindings, notifier, ::presenter) { appInForeground },
        captureGate = captureGate,
        mainExecutor = Executor { it.run() },
        crash = crash,
    )

    init {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun start(): GuardianTestRig {
        runtime.start()
        idle()
        return this
    }

    /** Waits until every call so far is applied and written (a few rounds: persistence can trigger outbox work). */
    fun idle() = runBlocking {
        withTimeout(20_000) {
            runtime.awaitIdle()
            runtime.awaitIdle()
        }
    }

    /** The process dies: nothing of this runtime runs any more. Room (the "disk") stays. */
    fun kill() {
        scope.cancel()
        when (executor) {
            is ExecutorService -> executor.shutdownNow()
            is AutoCloseable -> executor.close()
        }
    }

    fun startSession(vararg providers: Provider, keepSearching: Boolean = false, demo: Boolean = false, id: String = SESSION): String {
        val spec = SessionSpec(PICKUP, DROP, providers.toList(), listOf(VehicleClass.AUTO), keepSearching, id)
        if (demo) runtime.startDemoSession(spec) else runtime.startSession(spec)
        idle()
        return id
    }

    fun legId(provider: Provider): String = runtime.legIdFor(provider) ?: error("no leg for $provider")

    fun book(vararg providers: Provider) {
        providers.forEach { runtime.bookedHere(it) }
        idle()
    }

    /** The listener is connected (so the guardian can see which ride posts are still in the shade). */
    fun connectListener() {
        runtime.onListenerConnected(emptyList())
        idle()
    }

    fun post(provider: Provider, key: String, title: String?, text: String?, ongoing: Boolean = true, id: Int = 1) {
        val snapshot = PostSnapshot(
            packageName = provider.packageName, sbnKey = key, id = id, tag = null, channelId = "ride", category = "status",
            isOngoing = ongoing, title = title, text = text, postTimeMs = clock.wall, versionCode = 100L,
        )
        runtime.onRideAppPost(PostSource.of(snapshot))
        idle()
    }

    /** Uber and Rapido booked and searching; Rapido's captain accepts; the user says "Yes, cancel the others". */
    fun firstDriverChain() {
        startSession(Provider.UBER, Provider.RAPIDO)
        connectListener()
        book(Provider.UBER, Provider.RAPIDO)
        post(Provider.UBER, "u1", UBER_SEARCHING.first, UBER_SEARCHING.second)
        post(Provider.RAPIDO, "r1", RAPIDO_SEARCHING.first, RAPIDO_SEARCHING.second)
        post(Provider.RAPIDO, "r1", RAPIDO_ASSIGNED.first, RAPIDO_ASSIGNED.second)
        val sheet = ui.items.filterIsInstance<app.ridemate.notify.DriverSheetItem>().single()
        runtime.perform(app.ridemate.notify.GuardianTap(app.ridemate.notify.TapScope.SHEET, sheet.id, app.ridemate.core.guardian.UiAction.YES_CANCEL_OTHERS))
        idle()
    }

    fun remove(provider: Provider, key: String, reason: RemovalReason = RemovalReason.REASON_APP_CANCEL) {
        runtime.onRideAppRemoval(RemovalSource.of(PostRemoval(provider.packageName, key, reason)))
        idle()
    }

    fun advance(ms: Long) {
        clock.advance(ms)
    }

    /** One second of the session service: fire due guardian work, then tick the outbox. */
    fun serviceTick() {
        runtime.tick()
        idle()
        runBlocking { outboxDriver.tick() }
        idle()
    }

    val ui: GuardianUiState get() = runtime.uiState.value
    val status: GuardianStatus get() = runtime.status.value

    private val notificationManager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    fun notification(tag: String): Notification? = shadowOf(notificationManager).getNotification(tag, GuardianNotifier.ITEM_NOTIFICATION_ID)

    fun notifications(): List<Notification> = shadowOf(notificationManager).allNotifications

    fun actionTitles(tag: String): List<String> = notification(tag)?.actions.orEmpty().map { it.title.toString() }

    fun title(notification: Notification?): String? = notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    /** Makes [provider]'s app "installed" with a launcher activity, so it can be opened. */
    fun installApp(provider: Provider) {
        val component = ComponentName(provider.packageName, "${provider.packageName}.Main")
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    companion object {
        const val SESSION = "s1"
        val PICKUP = Place("Riverside Gate", LatLng(26.1445, 91.7362))
        val DROP = Place("Airport Terminal", LatLng(26.1112, 91.63442))

        // Posts the bundled templates recognise (assets/config/notification_templates.json).
        val RAPIDO_SEARCHING = "Rapido" to "Looking for a captain near you"
        val RAPIDO_ASSIGNED = "Captain assigned" to "Your captain Ravi is on the way. 4 min away"
        val RAPIDO_CANCELLED = "Ride cancelled" to "Your ride was cancelled"
        val UBER_SEARCHING = "Uber" to "Finding your ride"
        val UBER_ASSIGNED = "Uber" to "Your driver Amit is 5 mins away"
        val UBER_CANCELLED = "Trip cancelled" to "Your trip was cancelled"
        val OLA_SEARCHING = "Ola" to "Finding a cab near you"
        val OLA_CANCELLED = "Ride cancelled" to "Your ride has been cancelled"
    }
}
