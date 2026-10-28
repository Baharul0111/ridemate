package app.ridemate.compare

import android.content.Intent
import app.ridemate.AppGraph
import app.ridemate.bubble.BubbleHostRegistry
import app.ridemate.capture.CaptureManager
import app.ridemate.capture.CapturePurpose
import app.ridemate.capture.MediaProjectionGateway
import app.ridemate.capture.StopReason
import app.ridemate.crash.CrashSite
import app.ridemate.ocr.FrameMasker
import app.ridemate.ocr.MaskingRenderer
import app.ridemate.ocr.MlKitOcrEngine
import app.ridemate.ocr.OcrEngine
import app.ridemate.util.Clock
import kotlinx.coroutines.withContext

/**
 * Fare reading's part of the [AppGraph] (`graph.fareReading`): bubble host, capture, OCR + masking, the "Read fares"
 * use case, manual entry, the comparison list. Members are lazy.
 *
 * Integration binds (all optional; sensible fallbacks apply until then):
 * - [bubbleHosts]`.host` — the session service while it shows the bubble;
 * - [capture]`.foregroundHost` — the session service (adds the `mediaProjection` foreground type);
 * - [sessionCommands] — the guardian's "Booked here";
 * - [appLauncher] — the launcher area's AppLauncher (default: plain package launch);
 * - [activeComparison] — the session area's notion of the app on screen (default: Room).
 */
class FareReadingModule(private val graph: AppGraph) {
    val clock: Clock get() = graph.clock

    val bubbleHosts: BubbleHostRegistry = BubbleHostRegistry()

    @Volatile
    var sessionCommands: SessionCommands? = null

    @Volatile
    var appLauncher: FareAppLauncher = PackageLaunchLauncher(graph.app)

    @Volatile
    var activeComparison: ActiveComparison = ActiveComparison { RoomActiveComparison(graph.db).current() }

    val config: FareReadingConfig by lazy { RemoteFareReadingConfig(graph.configRepository) }

    val capture: CaptureManager by lazy {
        CaptureManager(
            context = graph.app,
            gateway = MediaProjectionGateway(graph.app, graph.clock),
            clock = graph.clock,
            crash = graph.crashReporter,
            overlays = { bubbleHosts.surface },
        )
    }

    val ocrEngine: OcrEngine by lazy { MlKitOcrEngine() }

    val masker: FrameMasker by lazy { MaskingRenderer(config = { config.maskConfig() }) }

    val quoteStore: QuoteStore by lazy { RoomQuoteStore(graph.db, graph.clock) }

    val installedVersions: InstalledVersions by lazy { InstalledVersions.of(graph.app) }

    val readFares: ReadFaresUseCase by lazy {
        ReadFaresUseCase(
            frames = capture,
            ocr = ocrEngine,
            masker = masker,
            api = graph.apiClient,
            auth = { graph.authGateway.state.value },
            config = config,
            store = quoteStore,
            clock = graph.clock,
            installedVersions = installedVersions,
            onAllFaresRead = { withContext(graph.dispatchers.main) { capture.stop(StopReason.FARE_READING_DONE) } },
            cpu = graph.dispatchers.default,
        )
    }

    val manualEntry: ManualEntryUseCase by lazy { ManualEntryUseCase(quoteStore, graph.clock) { config.sanityBands() } }

    val comparisons: ComparisonRepository by lazy { ComparisonRepository(graph.db) }

    val coordinator: FareReadingCoordinator by lazy {
        FareReadingCoordinator(
            context = graph.app,
            readFares = readFares,
            consent = { capture.obtainConsent(CapturePurpose.FARE_READING) },
            activeComparison = { activeComparison },
            bubble = { bubbleHosts.surface },
            clock = graph.clock,
            scope = graph.appScope,
            main = graph.dispatchers.main,
            openManualEntry = { leg, _ ->
                // Allowed while the bubble is visible; if Android refuses, the bubble's message still says what to do.
                runCatching {
                    graph.app.startActivity(
                        ComparisonActivity.intent(graph.app, leg.sessionId, leg.provider).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { graph.crashReporter.breadcrumb(CrashSite.UI) }
            },
            onError = { graph.crashReporter.recordNonFatal(it, CrashSite.CAPTURE) },
        )
    }

    /** Ends any running projection (session Done/Stop). Call on the main thread. */
    fun endCapture() = capture.stop(StopReason.SESSION_ENDED)
}
