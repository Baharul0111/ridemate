package app.ridemate.demo

import app.ridemate.auth.AuthGateway
import app.ridemate.auth.AuthState
import app.ridemate.core.config.FareParsersConfig
import app.ridemate.core.config.SanityBandsConfig
import app.ridemate.core.fare.FareParser
import app.ridemate.core.gate.ChannelARead
import app.ridemate.core.gate.DriverReading
import app.ridemate.core.gate.GatedRow
import app.ridemate.core.gate.SanityBands
import app.ridemate.core.gate.VerificationGate
import app.ridemate.core.model.Provider
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.net.ApiClient
import app.ridemate.net.ApiResult
import app.ridemate.net.ReadScreenRequest
import app.ridemate.net.TapBudget
import app.ridemate.util.Clock
import kotlinx.coroutines.CancellationException

/** Where Channel A's read of a demo screen came from (shown on every demo row). */
enum class ReadSource {
    /** Muse Spark Open read the image just now. */
    LIVE,

    /** The server's demo cache (an earlier live read of the same bytes). */
    CACHE,

    /** A recorded Muse Spark Open output, labelled "recorded" (server fallback, or the copy bundled in the app). */
    RECORDED,

    /** No read: every row says "Couldn't read · check in app". */
    NONE,
}

/** Why a demo read has no Channel A read or no rows. */
enum class DemoReadIssue {
    NONE,

    /** `ai_reader_enabled` is off: the recorded read is used without calling the server. */
    AI_SWITCHED_OFF,

    /** The server could not read it (offline, paused, not set up…) and no recorded read is bundled. */
    AI_UNAVAILABLE,

    /** K3: the OCR text has no ₹ token and no status keyword, so nothing was uploaded. */
    NOT_A_FARE_SCREEN,

    /** On-device OCR failed (ML Kit unavailable). */
    OCR_FAILED,

    /** `fare_parsers` has no rules for this demo provider. */
    NO_PARSER,
}

/**
 * One "Read fares" tap on a demo screen, through the real pipeline. Values reach the UI only as gated rows (enums,
 * numbers, OCR labels) and the source enum (I2, I5).
 */
data class DemoReadResult(
    val provider: Provider,
    val screen: String,
    val rows: List<GatedRow>,
    val driver: DriverReading,
    val source: ReadSource,
    /** True when [source] is a recorded read bundled in the app (the server was not reachable). */
    val recordedOnPhone: Boolean,
    /** The server's answer when it gave no read (for "what to do next" wording); null when it read the screen. */
    val apiFailure: ApiResult<*>?,
    val issue: DemoReadIssue,
    val seenAtMs: Long,
    /** SHA-256 of the uploaded bytes (equal to the bundled asset's). */
    val imageSha256: String,
)

/**
 * Reads a bundled demo screen the way the bubble reads a ride app: ML Kit OCR (Channel B through the demo provider's
 * `fare_parsers` rules), `/v1/read-screen` with `demo = true` (Channel A; the server serves allowlisted demo images from
 * its cache, the live model on a miss and recorded outputs as a fallback), then the core VerificationGate.
 *
 * - The uploaded bytes are the bundled PNG, unchanged (the server's allowlist matches their SHA-256). The demo screens
 *   carry only fake PII.
 * - Signed out, it first signs in anonymously: the server accepts anonymous tokens for allowlisted demo images, so the
 *   demo needs no Google sign-in and no AI budget.
 * - When the server can't answer (offline, not set up, AI paused or switched off), a recorded read bundled in the app
 *   is used and labelled "recorded"; without one every row honestly says "Couldn't read · check in app".
 * - The demo app is a still image, so both capture frames are the same OCR frame (§4.3 d holds trivially).
 * - No `/v1/reread`: crops of a demo frame are not on the server's allowlist and would need Google sign-in and AI
 *   budget, which the demo must not need. A disagreeing row stays "Couldn't read".
 */
class DemoFareReader(
    private val assets: DemoAssetSource,
    private val api: ApiClient,
    private val auth: AuthGateway,
    private val clock: Clock,
    private val aiReaderEnabled: () -> Boolean,
    private val fareParsers: () -> FareParsersConfig?,
    private val sanityBands: () -> SanityBandsConfig,
) {
    suspend fun read(
        provider: Provider,
        spec: DemoProviderSpec,
        screen: String,
        sessionId: String,
        tripKm: Double?,
        ocr: DemoOcr,
        budget: TapBudget,
    ): DemoReadResult {
        val path = spec.screens.getValue(screen)
        val bytes = assets.bytes(path)
        val sha = DemoImages.sha256(bytes)
        val seenAt = clock.wallMs()
        fun empty(issue: DemoReadIssue) = DemoReadResult(
            provider, screen, emptyList(), DriverReading.NONE, ReadSource.NONE, false, null, issue, seenAt, sha,
        )

        val parser = fareParsers()?.let { FareParser.forApp(it, provider, spec.versionCode) } ?: return empty(DemoReadIssue.NO_PARSER)
        val frame: OcrFrame = try {
            ocr.recognize(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return empty(DemoReadIssue.OCR_FAILED)
        }
        if (!parser.looksLikeFareScreen(frame)) return empty(DemoReadIssue.NOT_A_FARE_SCREEN)

        val channelA = channelA(provider, bytes, frame, sha, sessionId, budget)
        val gate = VerificationGate.verify(
            launchedApp = provider,
            read = channelA.read,
            frame1 = frame,
            frame2 = frame,
            parser = parser,
            bands = SanityBands(sanityBands()),
            tripKm = tripKm,
            nowMs = seenAt,
        )
        return DemoReadResult(
            provider = provider,
            screen = screen,
            rows = gate.rows,
            driver = gate.driver,
            source = channelA.source,
            recordedOnPhone = channelA.recordedOnPhone,
            apiFailure = channelA.failure,
            issue = channelA.issue,
            seenAtMs = seenAt,
            imageSha256 = sha,
        )
    }

    private class ChannelAOutcome(
        val read: ChannelARead?,
        val source: ReadSource,
        val recordedOnPhone: Boolean,
        val failure: ApiResult<*>?,
        val issue: DemoReadIssue,
    )

    private suspend fun channelA(
        provider: Provider,
        bytes: ByteArray,
        frame: OcrFrame,
        sha: String,
        sessionId: String,
        budget: TapBudget,
    ): ChannelAOutcome {
        if (!aiReaderEnabled()) return recordedOrNone(sha, failure = null, missingIssue = DemoReadIssue.AI_SWITCHED_OFF)
        ensureToken()
        val (width, height) = DemoImages.pngSize(bytes) ?: (frame.width to frame.height)
        val result = api.readScreen(ReadScreenRequest(provider, width, height, bytes, sessionId, demo = true), budget)
        if (result is ApiResult.Ok) {
            val read = ChannelARead.fromJsonOrNull(result.body.read.toString())
            if (read != null) return ChannelAOutcome(read, sourceOf(result.body.source), false, null, DemoReadIssue.NONE)
            return recordedOrNone(sha, failure = ApiResult.Http(200, MALFORMED), missingIssue = DemoReadIssue.AI_UNAVAILABLE)
        }
        return recordedOrNone(sha, failure = result, missingIssue = DemoReadIssue.AI_UNAVAILABLE)
    }

    private fun recordedOrNone(sha: String, failure: ApiResult<*>?, missingIssue: DemoReadIssue): ChannelAOutcome {
        val recorded = runCatching { assets.recordedRead(sha) }.getOrNull()
        return if (recorded != null) {
            val issue = if (missingIssue == DemoReadIssue.AI_SWITCHED_OFF) DemoReadIssue.AI_SWITCHED_OFF else DemoReadIssue.NONE
            ChannelAOutcome(recorded, ReadSource.RECORDED, true, failure, issue)
        } else {
            ChannelAOutcome(null, ReadSource.NONE, false, failure, missingIssue)
        }
    }

    /** Signed out → anonymous sign-in, so the server can serve its allowlisted demo cache without Google sign-in. */
    private suspend fun ensureToken() {
        if (auth.state.value !is AuthState.SignedOut) return
        try {
            auth.signInAnonymously()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or Auth down: the call below answers AuthNeeded and the recorded read is used.
        }
    }

    companion object {
        private const val MALFORMED = "malformed"

        fun sourceOf(wire: String): ReadSource = when (wire) {
            "cache" -> ReadSource.CACHE
            "recorded" -> ReadSource.RECORDED
            else -> ReadSource.LIVE
        }
    }
}
