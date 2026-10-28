@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.ridemate.demo

import app.ridemate.auth.DefaultAuthGateway
import app.ridemate.core.config.CancelCardsConfig
import app.ridemate.core.config.FareParsersConfig
import app.ridemate.core.config.NotificationTemplatesConfig
import app.ridemate.core.config.SanityBandsConfig
import app.ridemate.core.config.parseCancelCards
import app.ridemate.core.config.parseFareParsers
import app.ridemate.core.config.parseSanityBands
import app.ridemate.core.gate.ChannelARead
import app.ridemate.core.guardian.FeeRules
import app.ridemate.core.guardian.Guardian
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.GuardianTicker
import app.ridemate.core.guardian.RuleBasedFeeEstimator
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.model.Provider
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.core.state.TapId
import app.ridemate.core.watch.PostRemoval
import app.ridemate.core.watch.PostSnapshot
import app.ridemate.core.watch.PostSource
import app.ridemate.core.watch.RemovalSource
import app.ridemate.core.watch.RideWatchPipeline
import app.ridemate.net.ApiResult
import app.ridemate.net.ReadScreenResponse
import app.ridemate.testing.FakeAuthBackend
import app.ridemate.testing.FakeGoogleCredentialSource
import app.ridemate.testing.ProgrammableApiClient
import app.ridemate.util.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/** 28 Oct 2026 10:30 IST, so "seen at" stamps read like real ones. */
const val DEMO_TEST_WALL_BASE = 1_793_163_600_000L

/** The app's clock on the test scheduler's virtual time. */
class SchedulerAppClock(private val scheduler: TestCoroutineScheduler) : Clock {
    override fun elapsedRealtimeMs(): Long = 1_000_000L + scheduler.currentTime
    override fun wallMs(): Long = DEMO_TEST_WALL_BASE + scheduler.currentTime
}

/** Core's clock on the same virtual time (the guardian and RideWatch). */
class SchedulerCoreClock(private val scheduler: TestCoroutineScheduler) : app.ridemate.core.time.Clock {
    override fun elapsedMs(): Long = scheduler.currentTime
    override fun wallMs(): Long = DEMO_TEST_WALL_BASE + scheduler.currentTime
}

/** The bundled assets, read from `src/main/assets` (the bytes the APK ships). */
object BundledAssets {
    val dir: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main/assets"), File(it, "app/src/main/assets"), File(it, "android/app/src/main/assets")) }
            .first { File(it, "demo/script.json").isFile }
    }

    fun text(path: String): String = File(dir, path).readText()
    fun bytes(path: String): ByteArray = File(dir, path).readBytes()

    val script: DemoScript by lazy { DemoScript.parse(text(DemoScript.ASSET_PATH)) }
    val fareParsers: FareParsersConfig by lazy { parseFareParsers(text("config/fare_parsers.json")) }
    val sanityBands: SanityBandsConfig by lazy { parseSanityBands(text("config/sanity_bands.json")) }
    val cancelCards: CancelCardsConfig by lazy { parseCancelCards(text("config/cancel_cards.json")) }
    val templates: NotificationTemplatesConfig by lazy { NotificationTemplatesConfig.parse(text("config/notification_templates.json")) }
    val feeRules: FeeRules by lazy { FeeRules.parse(text("config/fee_rules.json")) }
}

/** Synthetic OCR and Channel A fixtures of the demo screens (copied from eval/synthetic, keyed by image SHA-256). */
object DemoFixtures {
    private val json = Json { ignoreUnknownKeys = true }

    private fun resource(path: String): String? =
        DemoFixtures::class.java.getResourceAsStream("/demo_fixtures/$path")?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }

    fun ocr(sha: String): OcrFrame = OcrFrame.fromJson(resource("ocr/$sha.json") ?: error("no OCR fixture for $sha"))

    /** The fixture's raw `read` object (what `/v1/read-screen` returns). */
    fun channelAJson(sha: String): JsonObject =
        (json.parseToJsonElement(resource("channel_a/$sha.json") ?: error("no Channel A fixture for $sha")) as JsonObject)["read"] as JsonObject

    fun channelA(sha: String): ChannelARead = ChannelARead.fromJson(channelAJson(sha).toString())

    fun recordedFile(sha: String): String = resource("channel_a/$sha.json") ?: error("no Channel A fixture for $sha")
}

/** The bundled assets, plus recorded reads the test chooses to "bundle". */
class FileDemoAssets(val recorded: MutableMap<String, String> = mutableMapOf()) : DemoAssetSource {
    var scriptOverride: String? = null
    override fun scriptText(): String = scriptOverride ?: BundledAssets.text(DemoScript.ASSET_PATH)
    override fun bytes(path: String): ByteArray = BundledAssets.bytes(path)
    override fun recordedRead(imageSha256: String): ChannelARead? = recorded[imageSha256]?.let { RecordedReads.parse(it, imageSha256) }

    /** Bundles the recorded read of every demo screen. */
    fun bundleAllRecorded(): FileDemoAssets = apply {
        BundledAssets.script.providers.values.flatMap { it.screens.values }.forEach { path ->
            val sha = DemoImages.sha256(BundledAssets.bytes(path))
            recorded[sha] = DemoFixtures.recordedFile(sha)
        }
    }
}

/** ML Kit stand-in: the synthetic OCR of the exact bytes it is given. */
class FixtureOcr : DemoOcr {
    var calls = 0
    var failWith: Exception? = null
    var override: ((ByteArray) -> OcrFrame)? = null

    override suspend fun recognize(image: ByteArray): OcrFrame {
        calls++
        failWith?.let { throw it }
        override?.let { return it(image) }
        return DemoFixtures.ocr(DemoImages.sha256(image))
    }
}

/** `/v1/read-screen` answering every demo image with its fixture read and the given [source]. */
fun ProgrammableApiClient.answerDemoReads(source: String = "recorded") {
    readScreen = { req ->
        ApiResult.Ok(ReadScreenResponse(DemoFixtures.channelAJson(DemoImages.sha256(req.image)), source, "test-model", 10, "req-1"))
    }
}

fun authGateway(backend: FakeAuthBackend, clock: Clock): DefaultAuthGateway =
    DefaultAuthGateway(backend, FakeGoogleCredentialSource(), clock)

/**
 * The session area's GuardianSessionApi stand-in, backed by the real core guardian and RideWatch pipeline wired as the
 * app wires them (demo flag on only during the demo session, templates from the bundled config, the guardian ticker on
 * virtual time).
 */
class CoreDemoHost(private val test: TestScope) : DemoSessionHost {
    private val clock = SchedulerCoreClock(test.testScheduler)
    private val effectFlow = MutableSharedFlow<GuardianEffect>(extraBufferCapacity = 4096)
    private val actionFlow = MutableSharedFlow<UserAction>(extraBufferCapacity = 4096)
    private var ticker: Job? = null

    lateinit var guardian: Guardian
        private set
    lateinit var pipeline: RideWatchPipeline
        private set

    /** Set to refuse the demo (a live session still has bookings to cancel). */
    var busy = false
    var ended = false
        private set
    val history = mutableListOf<Pair<Long, GuardianEffect>>()
    val opened = mutableListOf<String>()
    val quoted = mutableListOf<String>()
    val bookedFares = mutableMapOf<String, Int>()
    val verdicts = mutableListOf<Pair<String, Boolean>>()
    val driverReadings = mutableListOf<Triple<String, Int?, Int?>>()
    var startedKeepSearching: Boolean? = null

    override val effects: Flow<GuardianEffect> get() = effectFlow
    override val userActions: Flow<UserAction> get() = actionFlow

    override fun startDemoSession(sessionId: String, providers: List<Provider>, keepSearching: Boolean): Map<Provider, String>? {
        if (busy) return null
        startedKeepSearching = keepSearching
        guardian = Guardian(clock, RuleBasedFeeEstimator(BundledAssets.feeRules, stateCode = "AS"))
        guardian.effectListener = { effect ->
            history += test.testScheduler.currentTime to effect
            effectFlow.tryEmit(effect)
        }
        pipeline = RideWatchPipeline(clock, test.backgroundScope, guardian, guardian, { BundledAssets.templates })
        pipeline.demoSessionActive = true
        guardian.ongoingNotifications = pipeline
        guardian.startSession(sessionId, providers, keepSearching, demo = true)
        val g = guardian
        ticker = test.backgroundScope.launch { GuardianTicker(g, clock).run() }
        return providers.associateWith { "$sessionId:${it.wireId}" }
    }

    override fun post(post: PostSnapshot) = pipeline.onPosted(PostSource.of(post))
    override fun remove(removal: PostRemoval) = pipeline.onRemoved(RemovalSource.of(removal))

    override fun userAction(action: UserAction) {
        actionFlow.tryEmit(action)
        guardian.onUserAction(action)
    }

    override fun markOpened(legId: String) {
        opened += legId
        guardian.markOpened(legId)
    }

    override fun markQuoted(legId: String) {
        quoted += legId
        guardian.markQuoted(legId)
    }

    override fun recordBookedFare(legId: String, fareInr: Int) {
        bookedFares[legId] = fareInr
        guardian.recordBookedFare(legId, fareInr)
    }

    override fun captureVerdict(legId: String, cancelledKeywordsFound: Boolean, tapId: TapId) {
        verdicts += legId to cancelledKeywordsFound
        guardian.onCaptureVerdict(legId, cancelledKeywordsFound, tapId, clock.elapsedMs())
    }

    override fun verifiedDriverReading(legId: String, etaMin: Int?, distanceMeters: Int?, tapId: TapId) {
        driverReadings += Triple(legId, etaMin, distanceMeters)
        guardian.onVerifiedDriverReading(legId, etaMin, distanceMeters, tapId, clock.elapsedMs())
    }

    override fun endDemoSession() {
        ended = true
        ticker?.cancel()
        if (::pipeline.isInitialized) pipeline.demoSessionActive = false
    }

    inline fun <reified T : GuardianEffect> effectsOf(): List<Pair<Long, T>> = history.mapNotNull { (at, e) -> (e as? T)?.let { at to it } }

    fun leg(provider: Provider) = guardian.state.legs.values.first { it.provider == provider }
}

/** Everything a DemoController test needs, on the test's virtual time. */
class DemoHarness(
    val test: TestScope,
    val assets: FileDemoAssets = FileDemoAssets(),
    val authBackend: FakeAuthBackend = FakeAuthBackend(),
    var aiEnabled: Boolean = true,
) {
    val clock = SchedulerAppClock(test.testScheduler)
    val api = ProgrammableApiClient()
    val ocr = FixtureOcr()
    val host = CoreDemoHost(test)
    val demoEnabled = MutableStateFlow(true)
    val auth = authGateway(authBackend, clock)
    private var ids = 0
    val reader = DemoFareReader(
        assets = assets,
        api = api,
        auth = auth,
        clock = clock,
        aiReaderEnabled = { aiEnabled },
        fareParsers = { BundledAssets.fareParsers },
        sanityBands = { BundledAssets.sanityBands },
    )
    val controller = DemoController(
        assets = assets,
        reader = reader,
        api = api,
        clock = clock,
        scope = test.backgroundScope,
        demoEnabled = demoEnabled,
        cancelCards = { BundledAssets.cancelCards },
        newId = { "t${++ids}" },
    ).also { it.bind(ocr, host) }

    /** Virtual time at which each user step was taken (anchors for the script's checkpoints). */
    val anchors = linkedMapOf<String, Long>()

    val now: Long get() = test.testScheduler.currentTime

    fun runCurrent() = test.testScheduler.runCurrent()

    fun advance(ms: Long) {
        test.testScheduler.advanceTimeBy(ms)
        test.testScheduler.runCurrent()
    }

    fun anchor(step: DemoUserStep) {
        anchors.putIfAbsent(step.wire, now)
    }

    fun readAndBook(provider: Provider) {
        controller.readFares(provider)
        runCurrent()
        anchor(DemoUserStep.Book(provider))
        controller.bookedHere(provider)
        runCurrent()
    }

    fun tapCancel(provider: Provider) {
        anchor(DemoUserStep.TappedCancel(provider))
        controller.tapCancelRide(provider)
        runCurrent()
    }

    private var taps = 0
    fun tap(): TapId = TapId("ui-tap-${++taps}")

    /** The session area's sheet button. */
    fun answerSheet(sheetId: String, choice: app.ridemate.core.guardian.SheetChoice, step: DemoUserStep?) {
        step?.let(::anchor)
        host.userAction(UserAction.AnswerSheet(sheetId, choice, tap()))
        runCurrent()
    }
}
