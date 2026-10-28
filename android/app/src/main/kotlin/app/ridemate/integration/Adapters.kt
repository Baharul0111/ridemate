package app.ridemate.integration

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.ridemate.capture.CaptureForegroundHost
import app.ridemate.capture.CaptureManager
import app.ridemate.capture.CapturePurpose
import app.ridemate.capture.ConsentResult
import app.ridemate.capture.FrameImage
import app.ridemate.capture.FrameOrigin
import app.ridemate.capture.StopReason
import app.ridemate.compare.FareAppLauncher
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Place
import app.ridemate.core.model.Provider
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.core.state.TapId
import app.ridemate.core.watch.PostRemoval
import app.ridemate.core.watch.PostSnapshot
import app.ridemate.data.RideMateDb
import app.ridemate.demo.DemoOcr
import app.ridemate.demo.DemoSessionHost
import app.ridemate.demo.DemoTrip
import app.ridemate.dev.CaptureTestResult
import app.ridemate.dev.CaptureTester
import app.ridemate.dev.GoldenCaptureResult
import app.ridemate.dev.GoldenCaptureSource
import app.ridemate.dev.MaskedCapture
import app.ridemate.home.PreSessionGate
import app.ridemate.home.PreSessionIssue
import app.ridemate.home.PreSessionReport
import app.ridemate.launch.AppLauncher
import app.ridemate.launch.InstalledApps
import app.ridemate.launch.LaunchRequest
import app.ridemate.launch.LegOpenedSink
import app.ridemate.ocr.FrameMasker
import app.ridemate.ocr.OcrEngine
import app.ridemate.precheck.IssueSeverity
import app.ridemate.precheck.PreSessionCheck
import app.ridemate.precheck.PrecheckKind
import app.ridemate.precheck.PrecheckRequest
import app.ridemate.session.GuardianRuntime
import app.ridemate.session.SessionSpec
import app.ridemate.util.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Home's Compare gate over A2's [PreSessionCheck] (which also pings RideWatch), for the apps on this phone. */
class PrecheckGate(
    private val check: PreSessionCheck,
    private val installed: InstalledApps,
    private val ondcEnabled: () -> Boolean,
) : PreSessionGate {
    override suspend fun check(): PreSessionReport {
        val apps = runCatching { installed.installedRideApps().toSet() }.getOrDefault(emptySet())
        val report = check.run(PrecheckRequest(apps, includeOndc = ondcEnabled()))
        val blocking = mutableListOf<PreSessionIssue>()
        val warnings = mutableListOf<PreSessionIssue>()
        for (issue in report.issues) {
            val mapped = issueOf(issue.kind) ?: continue
            val list = if (issue.severity == IssueSeverity.BLOCKING) blocking else warnings
            if (mapped !in list) list += mapped
        }
        return PreSessionReport(blocking, warnings)
    }

    companion object {
        fun issueOf(kind: PrecheckKind): PreSessionIssue? = when (kind) {
            PrecheckKind.LISTENER_OFF, PrecheckKind.LISTENER_NOT_RESPONDING -> PreSessionIssue.LISTENER_OFF
            PrecheckKind.NO_SUPPORTED_APP -> PreSessionIssue.NO_SUPPORTED_APP
            PrecheckKind.NOTIFICATIONS_OFF -> PreSessionIssue.NOTIFICATIONS_OFF
            PrecheckKind.OVERLAY_OFF -> PreSessionIssue.OVERLAY_OFF
            PrecheckKind.BATTERY_RESTRICTED -> PreSessionIssue.BATTERY_OPTIMISED
            PrecheckKind.OFFLINE -> PreSessionIssue.OFFLINE
            PrecheckKind.LOCATION_DENIED -> PreSessionIssue.LOCATION_OFF
            // Chosen on the trip screen, or reported later in the session.
            PrecheckKind.NO_APP_SELECTED, PrecheckKind.NO_FARES_FOUND, PrecheckKind.NO_ONDC_RESULTS -> null
        }
    }
}

/** The comparison list's "Booked in <app>" → the guardian's BookedHere, carrying the user's own tap id (I1). */
class GuardianCompareCommands(private val runtime: () -> GuardianRuntime) : app.ridemate.compare.SessionCommands {
    override suspend fun bookedHere(provider: Provider, tapId: String): Boolean {
        val r = runtime()
        r.awaitRestored()
        val legId = r.legIdFor(provider) ?: return false
        r.onUserAction(UserAction.BookedHere(legId, TapId(tapId)))
        return true
    }
}

/** The launcher's SELECTED → OPENED goes to the guardian (which persists the leg row itself). */
class GuardianLegOpenedSink(private val runtime: () -> GuardianRuntime) : LegOpenedSink {
    override suspend fun legOpened(legId: String, provider: Provider, atWallMs: Long) = runtime().markOpenedLeg(legId)
}

/**
 * The comparison list's Book / refresh: AppLauncher with the active comparison's route (Uber universal link, Ola and
 * Rapido with the drop address to paste). The user taps Book inside the app.
 */
class RouteFareLauncher(
    private val launcher: () -> AppLauncher,
    private val db: () -> RideMateDb,
    private val installed: () -> InstalledApps,
    /** The guardian's current leg of [Provider] (after "Compare again" a re-added leg has a new id). */
    private val legIdFor: (Provider) -> String?,
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit = {},
) : FareAppLauncher {
    override fun open(provider: Provider): Boolean {
        if (provider.isDemo || provider.isNetwork) return false
        if (provider !in runCatching { installed().installedRideApps() }.getOrDefault(emptyList())) return false
        scope.launch {
            runCatching {
                val session = db().sessionDao().active().firstOrNull() ?: return@runCatching
                val pickup = ComparisonStarter.tripPlaceOf(session.pickup.toPlace())
                val drop = ComparisonStarter.tripPlaceOf(session.drop.toPlace())
                val legId = legIdFor(provider) ?: GuardianRuntime.legIdOf(session.id, provider)
                launcher().openFromUserTap(LaunchRequest(provider, pickup, drop, legId))
            }.onFailure(onError)
        }
        return true
    }
}

/** Try the demo's OCR: the capture area's ML Kit engine over the bundled PNG bytes (decoded in memory only). */
class OcrEngineDemoOcr(
    private val engine: () -> OcrEngine,
    private val clock: Clock,
    private val cpu: CoroutineDispatcher,
) : DemoOcr {
    override suspend fun recognize(image: ByteArray): OcrFrame {
        val bitmap = withContext(cpu) { BitmapFactory.decodeByteArray(image, 0, image.size) }
            ?: throw IllegalArgumentException("not an image")
        return FrameImage(bitmap, clock.elapsedRealtimeMs(), FrameOrigin.DEMO).use { engine().recognize(it) }
    }
}

/**
 * Try the demo's session host over the one guardian runtime of the process: a `demo = true` session of fictional
 * providers whose scripted posts go straight into RideWatch's pipeline (never through the listener), and whose
 * OpenApp / RequestCapture effects the guardian leaves to the demo.
 */
class GuardianDemoHost(
    private val runtime: () -> GuardianRuntime,
    private val trip: () -> DemoTrip?,
    private val clock: Clock,
) : DemoSessionHost {
    override val userActions: Flow<UserAction> get() = runtime().userActions
    override val effects: Flow<GuardianEffect> get() = runtime().effects

    override fun startDemoSession(sessionId: String, providers: List<Provider>, keepSearching: Boolean): Map<Provider, String>? {
        val r = runtime()
        val status = r.status.value
        // I8: a live session that still has bookings to cancel keeps running; the demo waits for it.
        if (status.sessionOpen && !status.demo && status.needsForegroundService) return null
        val t = trip()
        val pickup = t?.pickup?.let { Place(it.label, it.point) } ?: Place("Demo pickup", LatLng(26.1445, 91.7362))
        val drop = t?.drop?.let { Place(it.label, it.point) } ?: Place("Demo drop", LatLng(26.1158, 91.7086))
        r.startDemoSession(SessionSpec(pickup, drop, providers, DEMO_CLASSES, keepSearching, sessionId))
        return providers.associateWith { GuardianRuntime.legIdOf(sessionId, it) }
    }

    override fun post(post: PostSnapshot) = runtime().onDemoPost(post)
    override fun remove(removal: PostRemoval) = runtime().onDemoRemoval(removal)
    override fun userAction(action: UserAction) = runtime().onUserAction(action)
    override fun markOpened(legId: String) = runtime().markOpenedLeg(legId)
    override fun markQuoted(legId: String) = runtime().markQuotedLeg(legId)
    override fun recordBookedFare(legId: String, fareInr: Int) = runtime().recordBookedFareLeg(legId, fareInr)

    override fun captureVerdict(legId: String, cancelledKeywordsFound: Boolean, tapId: TapId) =
        runtime().captureVerdictLeg(legId, cancelledKeywordsFound, tapId, clock.elapsedRealtimeMs())

    override fun verifiedDriverReading(legId: String, etaMin: Int?, distanceMeters: Int?, tapId: TapId) =
        runtime().verifiedDriverReadingLeg(legId, etaMin, distanceMeters, tapId, clock.elapsedRealtimeMs())

    override fun endDemoSession() = runtime().endDemoSession()

    companion object {
        private val DEMO_CLASSES = listOf(VehicleClass.BIKE, VehicleClass.AUTO, VehicleClass.CAB_ECONOMY)
    }
}

/** The session service as capture's foreground host: the `mediaProjection` type through the guardian's gate. */
class GuardianCaptureHost(private val runtime: () -> GuardianRuntime) : CaptureForegroundHost {
    override fun enterMediaProjectionForeground(): Boolean = runtime().captureStarted()
    override fun exitMediaProjectionForeground() = runtime().captureEnded()
}

/**
 * Developer mode's capture test and golden captures (§4.19 b/c) over the real [CaptureManager]: Android's consent,
 * one frame of the owner's own screen, then the projection stops. Golden frames are masked (OcrAndMask, status bar
 * included) before they reach the developer screens; raw frames never leave memory.
 */
class CaptureDevTools(
    private val capture: () -> CaptureManager,
    private val ocr: () -> OcrEngine,
    private val masker: () -> FrameMasker,
    private val bubbleVisible: () -> Boolean,
    private val holdService: () -> AutoCloseable,
    private val clock: Clock,
    private val cpu: CoroutineDispatcher,
) : CaptureTester, GoldenCaptureSource {

    override suspend fun runCaptureTest(activity: Activity): CaptureTestResult = holdService().use {
        val manager = capture()
        when (val consent = manager.obtainConsent(CapturePurpose.CAPTURE_TEST)) {
            is ConsentResult.Granted -> {
                val frame = try {
                    manager.captureOnceAndStop(maxOf(consent.atElapsedMs, clock.elapsedRealtimeMs()))
                } finally {
                    manager.stopIfRunningFor(CapturePurpose.CAPTURE_TEST, StopReason.ONE_SHOT_DONE)
                }
                frame?.use { CaptureTestResult.Done(blackFrame = it.suspectedSecure, bubbleVisible = bubbleVisible()) }
                    ?: CaptureTestResult.Unavailable
            }
            ConsentResult.Denied, ConsentResult.NoAnswer -> CaptureTestResult.Denied
            ConsentResult.Failed -> CaptureTestResult.Unavailable
        }
    }

    override suspend fun captureMasked(activity: Activity): GoldenCaptureResult = holdService().use {
        val manager = capture()
        when (val consent = manager.obtainConsent(CapturePurpose.CAPTURE_TEST)) {
            is ConsentResult.Granted -> {
                val frame = try {
                    manager.captureOnceAndStop(maxOf(consent.atElapsedMs, clock.elapsedRealtimeMs()))
                } finally {
                    manager.stopIfRunningFor(CapturePurpose.CAPTURE_TEST, StopReason.ONE_SHOT_DONE)
                } ?: return@use GoldenCaptureResult.Unavailable
                frame.use { f ->
                    if (f.suspectedSecure) return@use GoldenCaptureResult.BlackFrame
                    val tokens = ocr().recognize(f)
                    masker().maskForUpload(f, tokens).use { masked ->
                        val png = withContext(cpu) { pngOf(masked.jpeg) } ?: return@use GoldenCaptureResult.Unavailable
                        GoldenCaptureResult.Captured(MaskedCapture(png, masked.imageWidth, masked.imageHeight))
                    }
                }
            }
            ConsentResult.Denied, ConsentResult.NoAnswer -> GoldenCaptureResult.Denied
            ConsentResult.Failed -> GoldenCaptureResult.Unavailable
        }
    }

    private fun pngOf(jpeg: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val PNG_QUALITY = 100
    }
}
