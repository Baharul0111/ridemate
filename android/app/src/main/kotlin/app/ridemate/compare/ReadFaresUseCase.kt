package app.ridemate.compare

import app.ridemate.auth.AuthState
import app.ridemate.capture.BlackFrameDetector
import app.ridemate.capture.CaptureOutcome
import app.ridemate.capture.FareFrameSource
import app.ridemate.capture.FrameImage
import app.ridemate.capture.FrameTiming
import app.ridemate.core.fare.FareParser
import app.ridemate.core.fare.looksLikeFareScreen
import app.ridemate.core.gate.ChannelARead
import app.ridemate.core.gate.GateResult
import app.ridemate.core.gate.RereadResponse
import app.ridemate.core.gate.SanityBands
import app.ridemate.core.gate.VerificationGate
import app.ridemate.core.model.Provider
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.net.ApiClient
import app.ridemate.net.ApiResult
import app.ridemate.net.ReadScreenRequest
import app.ridemate.net.RereadCrop
import app.ridemate.net.RereadRequest
import app.ridemate.net.TapBudget
import app.ridemate.ocr.FrameMasker
import app.ridemate.ocr.ImageTooLargeException
import app.ridemate.ocr.MaskedUpload
import app.ridemate.ocr.OcrEngine
import app.ridemate.ocr.OcrFailedException
import app.ridemate.util.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * "Read fares" (§4.2, §4.3): one user tap → a checked price list, or an honest reason why not. Never hangs: the
 * whole tap runs under its 20 s deadline ([TapBudget.TOTAL_MS]).
 *
 * 1. AI pre-check (kill switch, sign-in, parser present) → manual mode without capturing anything.
 * 2. Two frames 400 ms apart; I6: discarded unless taken and processed within 2 s of the tap.
 * 3. ML Kit OCR of both frames (Channel B tokens); a black frame with no text is FLAG_SECURE → share fallback.
 * 4. K3: no ₹/Rs token and no status keyword → "This doesn't look like a fare screen", nothing uploaded.
 * 5. PII masked (tokens of both frames; pixels of frame 2) → `/v1/read-screen` with frame 2 within the tap budget
 *    (the API client applies the single retry rule of §4.12). Any failure → manual mode.
 * 6. Core [VerificationGate] over both frames, the parser, the sanity bands and the trip distance; if it asks for a
 *    re-read and the budget allows, the disagreeing rows are cropped from the masked frame and re-read once.
 * 7. Rows → Room (VERIFIED / UNREADABLE), leg → QUOTED. When no leg waits for a read any more, [onAllFaresRead]
 *    stops the projection (right after the last fare read).
 */
class ReadFaresUseCase(
    private val frames: FareFrameSource,
    private val ocr: OcrEngine,
    private val masker: FrameMasker,
    private val api: ApiClient,
    private val auth: () -> AuthState,
    private val config: FareReadingConfig,
    private val store: QuoteStore,
    private val clock: Clock,
    private val installedVersions: InstalledVersions,
    private val onAllFaresRead: suspend () -> Unit = {},
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val deadlineMs: Long = TapBudget.TOTAL_MS,
) {

    /** Manual mode decided before any capture (no consent prompt, no OCR, nothing uploaded); null when AI may run. */
    fun precheck(provider: Provider): ReadFaresResult.ManualMode? {
        val reason = when {
            !config.aiReaderEnabled(provider) -> ManualReason.KILL_SWITCH
            else -> when (auth()) {
                AuthState.SignedOut -> ManualReason.SIGNED_OUT
                is AuthState.Anonymous -> if (provider.isDemo) null else ManualReason.ANONYMOUS
                is AuthState.Google -> null
            }
        } ?: if (parserFor(provider) == null) ManualReason.READER_UNAVAILABLE else null
        return reason?.let { ReadFaresResult.ManualMode(provider, it) }
    }

    /** Captures and reads the screen for [request]. */
    suspend fun read(request: ReadFaresRequest): ReadFaresResult {
        precheck(request.provider)?.let { return it }
        return withDeadline(request) {
            when (val captured = frames.captureTwoFrames(request.tapAtElapsedMs)) {
                CaptureOutcome.NotActive -> ReadFaresResult.NeedsConsent
                CaptureOutcome.Failed -> ReadFaresResult.CaptureFailed
                is CaptureOutcome.Frames -> captured.use { process(request, it.first, it.second) }
            }
        }
    }

    /**
     * Reads a screenshot the user shared (the FLAG_SECURE fallback). The share is the user's tap, so [request]'s tap
     * time is when it arrived; the one image stands in for both frames. [frame] stays the caller's to close.
     */
    suspend fun readShared(request: ReadFaresRequest, frame: FrameImage): ReadFaresResult {
        precheck(request.provider)?.let { return it }
        return withDeadline(request) { process(request, frame, frame) }
    }

    private suspend fun withDeadline(request: ReadFaresRequest, block: suspend () -> ReadFaresResult): ReadFaresResult {
        val remaining = request.tapAtElapsedMs + deadlineMs - clock.elapsedRealtimeMs()
        if (remaining <= 0) return ReadFaresResult.TimedOut
        return withTimeoutOrNull(remaining) { block() } ?: ReadFaresResult.TimedOut
    }

    private suspend fun process(request: ReadFaresRequest, first: FrameImage, second: FrameImage): ReadFaresResult {
        val provider = request.provider
        val processAt = clock.elapsedRealtimeMs()
        for (frame in listOf(first, second)) {
            val verdict = FrameTiming.check(request.tapAtElapsedMs, frame.capturedAtElapsedMs, processAt, frame.origin)
            if (verdict != FrameTiming.Verdict.OK) return ReadFaresResult.TooSlow(verdict)
        }
        val version = installedVersions.versionCode(provider)
        val parsers = config.fareParsers()
        val parser = parsers?.let { FareParser.forApp(it, provider, version) }
            ?: return ReadFaresResult.ManualMode(provider, ManualReason.READER_UNAVAILABLE)

        val (ocr1, ocr2) = try {
            recognizeBoth(first, second)
        } catch (e: OcrFailedException) {
            return ReadFaresResult.CaptureFailed
        }
        if (looksSecure(first, ocr1) && looksSecure(second, ocr2)) return ReadFaresResult.CaptureBlocked(provider)
        if (!looksLikeFareScreen(ocr2, provider, parsers, version)) return ReadFaresResult.NotFareScreen(provider)

        val frame1Masked = ocr1.copy(tokens = masker.maskTokensOnly(ocr1).maskedTokens)
        // Non-cancellable so a deadline can't drop the result before `use` owns (and recycles) the masked copy.
        val upload = try {
            withContext(cpu + NonCancellable) { masker.maskForUpload(second, ocr2) }
        } catch (e: ImageTooLargeException) {
            return ReadFaresResult.CaptureFailed
        }
        return upload.use { masked -> readMasked(request, parser, version, frame1Masked, masked, second.capturedAtElapsedMs) }
    }

    private suspend fun readMasked(
        request: ReadFaresRequest,
        parser: FareParser,
        version: Long?,
        frame1: OcrFrame,
        upload: MaskedUpload,
        frameAtElapsedMs: Long,
    ): ReadFaresResult {
        val provider = request.provider
        val budget = TapBudget(request.tapAtElapsedMs + deadlineMs)
        val response = api.readScreen(
            ReadScreenRequest(provider, upload.imageWidth, upload.imageHeight, upload.jpeg, request.sessionId),
            budget,
        )
        val body = (response as? ApiResult.Ok)?.body
            ?: return manual(provider, response, suggestions(parser, upload.maskedOcr))
        val read = ChannelARead.fromJsonOrNull(body.read.toString())
        val seenAtWallMs = clock.wallMs() - (clock.elapsedRealtimeMs() - frameAtElapsedMs).coerceAtLeast(0)
        val bands = SanityBands(config.sanityBands())
        var gate: GateResult = withContext(cpu) {
            VerificationGate.verify(provider, read, frame1, upload.maskedOcr, parser, bands, request.tripKm, seenAtWallMs)
        }
        var rereadUsed = false
        val plan = VerificationGate.planReread(gate, request.tapAtElapsedMs, clock.elapsedRealtimeMs(), firstCallTimedOut = false)
        if (plan != null) {
            val crops = withContext(cpu) { upload.cropJpegs(plan.crops.map { it.rowId to it.box }) }
            if (crops != null) {
                val reread = api.reread(RereadRequest(provider, crops.map { RereadCrop(it.rowId, it.jpeg) }, request.sessionId), budget)
                val rows = (reread as? ApiResult.Ok)?.body?.rows
                val decoded = rows?.let { runCatching { RereadResponse.fromJson(JsonObject(mapOf("rows" to JsonArray(it))).toString()) }.getOrNull() }
                if (decoded != null) {
                    gate = VerificationGate.applyReread(gate, decoded)
                    rereadUsed = true
                }
            }
        }
        val saved = gate.rows.isNotEmpty() && store.saveRead(request.legId, gate.rows, version, upload.sha256)
        if (saved && store.pendingReads(request.sessionId) == 0) onAllFaresRead()
        return ReadFaresResult.Read(provider, gate.rows, gate.driver, rereadUsed, saved)
    }

    private suspend fun recognizeBoth(first: FrameImage, second: FrameImage): Pair<OcrFrame, OcrFrame> = coroutineScope {
        if (first === second) {
            val only = ocr.recognize(first)
            only to only
        } else {
            val a = async { ocr.recognize(first) }
            val b = async { ocr.recognize(second) }
            a.await() to b.await()
        }
    }

    /** FLAG_SECURE: the pixels are mostly uniform dark AND OCR found no text in the content band. */
    private fun looksSecure(frame: FrameImage, ocr: OcrFrame): Boolean =
        frame.suspectedSecure && ocr.tokens.none { BlackFrameDetector.inContentBand(it.box.centerY, ocr.height) }

    private fun parserFor(provider: Provider): FareParser? =
        config.fareParsers()?.let { FareParser.forApp(it, provider, installedVersions.versionCode(provider)) }

    private fun manual(provider: Provider, result: ApiResult<*>, suggested: List<VehicleClass>): ReadFaresResult.ManualMode =
        ReadFaresResult.ManualMode(
            provider = provider,
            reason = ManualReason.of(result),
            resumeAtIst = (result as? ApiResult.AiPaused)?.resumeAtIst,
            apiResult = result,
            suggestedClasses = suggested,
        )

    /** Vehicle classes the phone's own reader saw (labels only; prices never leave the gate unverified). */
    private fun suggestions(parser: FareParser, frame: OcrFrame): List<VehicleClass> =
        runCatching { parser.parse(frame).rows.mapNotNull { it.vehicleClass }.distinct() }.getOrDefault(emptyList())
}
