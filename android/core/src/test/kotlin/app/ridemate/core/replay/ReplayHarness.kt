@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.ridemate.core.replay

import app.ridemate.core.config.NotificationTemplatesConfig
import app.ridemate.core.guardian.FeeRules
import app.ridemate.core.guardian.Guardian
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.RuleBasedFeeEstimator
import app.ridemate.core.guardian.TimedEffect
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.state.TapId
import app.ridemate.core.time.Clock
import app.ridemate.core.time.ManualClock
import app.ridemate.core.watch.ClassifierLabel
import app.ridemate.core.watch.ClassifierResult
import app.ridemate.core.watch.ClassifyRequest
import app.ridemate.core.watch.NotificationClassifier
import app.ridemate.core.watch.PostAuditEntry
import app.ridemate.core.watch.PostRemoval
import app.ridemate.core.watch.PostSnapshot
import app.ridemate.core.watch.PostSource
import app.ridemate.core.watch.RemovalReason
import app.ridemate.core.watch.RemovalSource
import app.ridemate.core.watch.RideWatchPipeline
import app.ridemate.core.watch.TextMasker
import app.ridemate.core.watch.UnavailableReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import java.io.IOException

/** Virtual time from the test scheduler. [reboot] restarts the monotonic clock while wall time keeps going. */
class SchedulerClock(private val scheduler: TestCoroutineScheduler, private val wallBase: Long = ManualClock.DEFAULT_WALL_MS) : Clock {
    private var bootAt = 0L
    override fun elapsedMs(): Long = scheduler.currentTime - bootAt
    override fun wallMs(): Long = wallBase + scheduler.currentTime
    fun reboot() {
        bootAt = scheduler.currentTime
    }
}

/** A stand-in for `/v1/parse-notification`: never touches the network. */
class FakeClassifier(var mode: Mode = Mode.LABELS, var latencyMs: Long = 1_500L) : NotificationClassifier {
    enum class Mode { LABELS, DOWN, ANONYMOUS, HANG }

    /** Masked-text substring → label; anything else is `unknown`. */
    val rules = mutableListOf<Pair<String, ClassifierLabel>>()
    val requests = mutableListOf<ClassifyRequest>()

    override suspend fun classify(request: ClassifyRequest): ClassifierResult {
        requests += request
        return when (mode) {
            Mode.DOWN -> throw IOException("backend down")
            Mode.ANONYMOUS -> ClassifierResult.Unavailable(UnavailableReason.ANONYMOUS)
            Mode.HANG -> awaitCancellation()
            Mode.LABELS -> {
                delay(latencyMs)
                val text = request.title + "\n" + request.text
                ClassifierResult.Labelled(rules.firstOrNull { text.contains(it.first, ignoreCase = true) }?.second ?: ClassifierLabel.UNKNOWN)
            }
        }
    }
}

/**
 * RideWatch + statechart + guardian wired exactly as the app wires them, on virtual time. Posts go in through
 * [RideWatchPipeline.onPosted]; the guardian's ticker runs on the same dispatcher.
 */
class ReplayHarness(
    private val test: TestScope,
    providers: List<Provider> = listOf(Provider.UBER, Provider.OLA, Provider.RAPIDO),
    keepSearching: Boolean = false,
    private val templates: NotificationTemplatesConfig = Fixtures.templates,
    val classifier: FakeClassifier? = FakeClassifier(),
    private val masker: TextMasker = TextMasker.NONE,
    startTicker: Boolean = true,
) {
    val clock = SchedulerClock(test.testScheduler)
    val fees = RuleBasedFeeEstimator(FeeRules.parse(FEE_RULES_JSON), stateCode = "AS")
    var guardian: Guardian = Guardian(clock, fees)
        private set
    var pipeline: RideWatchPipeline = newPipeline()
        private set

    /** What the shade currently shows, re-read on (re)connect. */
    private val active = LinkedHashMap<String, PostSnapshot>()
    private var ticker: Job? = null
    private var taps = 0

    init {
        listen(guardian)
        guardian.startSession(SESSION, providers, keepSearching)
        if (startTicker) startTicker()
    }

    /** Every effect across restarts, stamped with virtual time. */
    val history = mutableListOf<Pair<Long, GuardianEffect>>()

    private fun listen(g: Guardian) {
        g.effectListener = { history += test.testScheduler.currentTime to it }
    }

    private fun newPipeline() = RideWatchPipeline(
        clock, test.backgroundScope, guardian, guardian, { templates }, classifier, masker,
    ).also { guardian.ongoingNotifications = it }

    fun startTicker() {
        ticker?.cancel()
        val g = guardian
        ticker = test.backgroundScope.launch { app.ridemate.core.guardian.GuardianTicker(g, clock).run() }
    }

    fun legId(provider: Provider): String = "$SESSION:${provider.wireId}"
    fun record(provider: Provider) = guardian.state.legs.getValue(legId(provider))
    fun state(provider: Provider): RideState = record(provider).leg.state
    fun generation(provider: Provider): Int = record(provider).leg.generation
    fun tap(): TapId = TapId("tap-${++taps}")
    val now: Long get() = clock.elapsedMs()

    fun act(action: UserAction): List<GuardianEffect> = guardian.onUserAction(action).also { test.testScheduler.runCurrent() }

    fun book(vararg providers: Provider) = providers.forEach { act(UserAction.BookedHere(legId(it), tap())) }

    fun post(
        pkg: String,
        key: String,
        title: String?,
        text: String?,
        id: Int = 1,
        tag: String? = null,
        channel: String? = Fixtures.rideChannel(pkg),
        ongoing: Boolean = true,
        versionCode: Long? = 100L,
        redacted: Boolean = false,
    ): PostAuditEntry {
        val snapshot = PostSnapshot(pkg, key, id, tag, channel, "status", ongoing, title, text, clock.wallMs(), versionCode, redacted)
        active[key] = snapshot
        pipeline.onPosted(PostSource.of(snapshot))
        test.testScheduler.runCurrent()
        return pipeline.audit.last()
    }

    fun post(pkg: String, key: String, content: Pair<String, String>, id: Int = 1, ongoing: Boolean = true): PostAuditEntry =
        post(pkg, key, content.first, content.second, id = id, ongoing = ongoing)

    fun remove(pkg: String, key: String, reason: RemovalReason = RemovalReason.REASON_APP_CANCEL) {
        active.remove(key)
        pipeline.onRemoved(RemovalSource.of(PostRemoval(pkg, key, reason)))
        test.testScheduler.runCurrent()
    }

    fun advance(ms: Long) {
        test.testScheduler.advanceTimeBy(ms)
        test.testScheduler.runCurrent()
    }

    /** The app's process dies and comes back: state from the snapshot, a new RideWatch that re-reads the shade. */
    fun restart(reboot: Boolean = false) {
        val snapshot = guardian.snapshot()
        ticker?.cancel()
        if (reboot) clock.reboot()
        guardian = Guardian.restore(snapshot, clock, fees)
        listen(guardian)
        pipeline = newPipeline()
        guardian.resume()
        startTicker()
        pipeline.onListenerConnected(active.values.map { PostSource.of(it) })
        test.testScheduler.runCurrent()
    }

    val effects: List<TimedEffect> get() = guardian.effectLog

    inline fun <reified T : GuardianEffect> historyOf(): List<Pair<Long, T>> =
        history.mapNotNull { (at, e) -> (e as? T)?.let { at to it } }

    fun mark(): Int = effects.size

    inline fun <reified T : GuardianEffect> effectsOf(since: Int = 0): List<T> =
        effects.drop(since).map { it.effect }.filterIsInstance<T>()

    inline fun <reified T : GuardianEffect> timedOf(since: Int = 0): List<Pair<Long, T>> =
        effects.drop(since).mapNotNull { (at, e) -> (e as? T)?.let { at to it } }

    companion object {
        const val SESSION = "s1"

        /** contracts §2.5, verbatim. */
        val FEE_RULES_JSON = """
            {"schema": "1.0", "default": {"pct": 10, "max_inr": 100, "free_while_searching": true},
             "apps": {"com.olacabs.customer": {"free_within_s_after_accept": 180, "free_if_driver_late_min": 5, "max_inr": 100},
                      "com.ubercab": {"free_within_s_after_accept": 120, "max_inr": 100},
                      "com.rapido.passenger": {"free_within_s_after_accept": 60, "max_inr": 50}},
             "states": {"AS": {"max_inr": 100}}}
        """.trimIndent()
    }
}
