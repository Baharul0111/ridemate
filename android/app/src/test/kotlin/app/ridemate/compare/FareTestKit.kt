package app.ridemate.compare

import android.graphics.Bitmap
import app.ridemate.capture.CaptureOutcome
import app.ridemate.capture.FareFrameSource
import app.ridemate.capture.FrameImage
import app.ridemate.capture.FrameOrigin
import app.ridemate.core.config.FareParsersConfig
import app.ridemate.core.config.SanityBandsConfig
import app.ridemate.core.config.parseFareParsers
import app.ridemate.core.fare.FareParser
import app.ridemate.core.gate.ChannelAOption
import app.ridemate.core.gate.ChannelARead
import app.ridemate.core.mask.MaskResult
import app.ridemate.core.mask.maskTokens
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Provider
import app.ridemate.core.ocr.Box
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.core.ocr.OcrToken
import app.ridemate.net.ApiClient
import app.ridemate.net.ApiResult
import app.ridemate.net.ConfigCheckResponse
import app.ridemate.net.EraseResponse
import app.ridemate.net.OndcSearchResponse
import app.ridemate.net.ParseNotificationRequest
import app.ridemate.net.ParseNotificationResponse
import app.ridemate.net.PlacePickRequest
import app.ridemate.net.PlacePickResponse
import app.ridemate.net.PointRequest
import app.ridemate.net.PointResponse
import app.ridemate.net.ReadScreenRequest
import app.ridemate.net.ReadScreenResponse
import app.ridemate.net.RereadRequest
import app.ridemate.net.RereadResponse
import app.ridemate.net.SessionEventReport
import app.ridemate.net.TapBudget
import app.ridemate.ocr.FrameMasker
import app.ridemate.ocr.MaskedUpload
import app.ridemate.ocr.OcrEngine
import app.ridemate.ocr.RowCropJpeg
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt

/** Builds OCR frames the way ML Kit reports them (words with pixel boxes, numbered lines), like core's test kit. */
class FareFrames(private val width: Int = 1080, private val height: Int = 2400) {
    private val tokens = mutableListOf<OcrToken>()
    private var nextLine = 0

    fun line(left: Int, top: Int, h: Int, vararg words: String): FareFrames {
        val lineId = nextLine++
        var x = left
        for (w in words) {
            val wWidth = glyphWidth(w, h)
            tokens += OcrToken(w, Box(x, top, x + wWidth, top + h), lineId, 0)
            x += wWidth + (0.3 * h).roundToInt()
        }
        return this
    }

    fun right(text: String, right: Int, top: Int, h: Int): FareFrames {
        tokens += OcrToken(text, Box(right - glyphWidth(text, h), top, right, top + h), nextLine++, 1)
        return this
    }

    fun build(): OcrFrame = OcrFrame(width, height, tokens.toList())

    companion object {
        fun glyphWidth(text: String, h: Int): Int = (text.length * 0.55 * h).roundToInt().coerceAtLeast(1)

        /** A fare list: label + right-aligned payable on one line, ETA below, like the demo screens. */
        fun fareScreen(rows: List<Triple<String, String, String?>>, header: String? = "Choose a ride"): OcrFrame {
            val b = FareFrames()
            b.line(40, 20, 30, "10:42", "4G")
            if (header != null) b.line(60, 300, 44, *header.split(" ").toTypedArray())
            rows.forEachIndexed { i, (label, price, eta) ->
                val y = 420 + i * 180
                b.line(150, y, 40, *label.split(" ").toTypedArray())
                b.right(price, 1000, y - 4, 48)
                if (eta != null) b.line(150, y + 60, 30, *eta.split(" ").toTypedArray())
            }
            return b.build()
        }

        val STANDARD_ROWS = listOf(
            Triple("Bike", "₹45", "2 min away"),
            Triple("Auto", "₹110", "3 min away"),
            Triple("Cab Economy", "₹210", "5 min away"),
        )

        val STANDARD: OcrFrame = fareScreen(STANDARD_ROWS)

        /** No ₹ amount and no status keyword: K3 must reject it. */
        val HOME_SCREEN: OcrFrame = FareFrames().line(40, 20, 30, "10:42").line(60, 600, 44, "Where", "to?").build()
    }
}

object FareConfigs {
    private const val ENTRY = """
      {"min_version_code": 0,
       "vehicle_labels": [{"pattern": "(?i)\\b(?:bike|moto)\\b", "vehicle_class": "bike"},
                          {"pattern": "(?i)\\bauto\\b", "vehicle_class": "auto"},
                          {"pattern": "(?i)\\b(?:xl|suv)\\b", "vehicle_class": "cab_xl"},
                          {"pattern": "(?i)\\b(?:premier|prime|sedan|premium)\\b", "vehicle_class": "cab_premium"},
                          {"pattern": "(?i)\\b(?:uber go|cab economy|cab|mini|go|economy)\\b", "vehicle_class": "cab_economy"}],
       "price_token_pattern": "(?i)(?:₹|rs\\.?|inr)\\s*[0-9०-९][0-9०-९,]*(?:\\s*[-–]\\s*(?:₹|rs\\.?)?\\s*[0-9०-९][0-9०-९,]*)?",
       "eta_pattern": "(?i)(\\d+)\\s*(?:min|mins|मिनट)",
       "strike_height_ratio": 0.8,
       "row_gap_ratio": 1.8,
       "driver_eta_anchors": ["away", "arriving", "pickup", "मिनट", "दूर"],
       "driver_distance_pattern": "(?i)(\\d+(?:\\.\\d+)?)\\s*(km|m)\\b",
       "screen_keywords": {"fare_options": ["choose a ride", "book"], "searching": ["looking for", "finding"],
                           "driver_assigned": ["arriving", "on the way", "otp"], "cancelled": ["cancelled"]}}
    """

    val FARE_PARSERS_JSON: String = buildString {
        append("""{"schema": "1.0", "apps": {""")
        append(Provider.entries.filterNot { it.isNetwork }.joinToString(",") { "\"${it.packageName}\": [$ENTRY]" })
        append("}}")
    }

    val parsers: FareParsersConfig by lazy { parseFareParsers(FARE_PARSERS_JSON) }

    fun parser(app: Provider = Provider.UBER): FareParser = FareParser.forApp(parsers, app, 1)!!

    /** A 6.2 km trip: the ₹/km bands accept the standard screen's ₹45 bike, ₹110 auto and ₹210 cab. */
    const val TRIP_KM: Double = 6.2
}

class FakeFareReadingConfig(
    var parsers: FareParsersConfig? = FareConfigs.parsers,
    var bands: SanityBandsConfig = SanityBandsConfig.DEFAULT,
    var aiEnabled: Boolean = true,
) : FareReadingConfig {
    override fun fareParsers(): FareParsersConfig? = parsers
    override fun sanityBands(): SanityBandsConfig = bands
    override fun aiReaderEnabled(provider: Provider): Boolean = aiEnabled
}

/** What a correct Channel A would say about [frame] (the parser's own rows on the 0–1000 grid). */
fun perfectRead(frame: OcrFrame, app: Provider = Provider.UBER, change: (Int, ChannelAOption) -> ChannelAOption = { _, o -> o }): ChannelARead {
    val read = FareConfigs.parser(app).parse(frame)
    return ChannelARead(
        app = app,
        screenType = read.screenType,
        options = read.rows.mapIndexed { i, row ->
            change(
                i,
                ChannelAOption(
                    vehicleClass = row.vehicleClass,
                    labelText = row.label?.text,
                    priceMin = row.price?.amount?.min,
                    priceMax = row.price?.amount?.max,
                    etaMin = row.eta?.value,
                    bbox = row.box.toGrid(frame.width, frame.height).toList(),
                ),
            )
        },
    )
}

fun ChannelARead.asJsonObject(): JsonObject = Json.parseToJsonElement(toJson()).jsonObject

fun readOk(read: ChannelARead): ApiResult<ReadScreenResponse> =
    ApiResult.Ok(ReadScreenResponse(read.asJsonObject(), "live", "meta-models/Muse-Spark-Open-816B-A42B", 2000, "req-1"))

/** A tiny in-memory frame; OCR is faked, so the pixels don't matter. */
fun frameAt(capturedAtElapsedMs: Long, origin: FrameOrigin = FrameOrigin.CAPTURE, suspectedSecure: Boolean = false): FrameImage =
    FrameImage(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), capturedAtElapsedMs, origin, suspectedSecure)

/** Hands out scripted capture outcomes and remembers the frames it gave. */
class FakeFrameSource(var next: (tapAt: Long) -> CaptureOutcome) : FareFrameSource {
    val calls = mutableListOf<Long>()
    val handedOut = mutableListOf<FrameImage>()

    override suspend fun captureTwoFrames(tapAtElapsedMs: Long): CaptureOutcome {
        calls += tapAtElapsedMs
        return next(tapAtElapsedMs).also { if (it is CaptureOutcome.Frames) handedOut += listOf(it.first, it.second) }
    }
}

/** OCR answers per frame (by identity); [hang] makes recognition never return. */
class FakeOcrEngine(var forFrame: (FrameImage) -> OcrFrame) : OcrEngine {
    var hang = false
    var calls = 0

    override suspend fun recognize(frame: FrameImage): OcrFrame {
        calls++
        if (hang) awaitCancellation()
        return forFrame(frame)
    }
}

/** Masks tokens with core (the real PII rules) but skips pixels: the "JPEG" is a fixed JPEG header + marker. */
class FakeMasker : FrameMasker {
    var uploads = 0
    val closed = mutableListOf<FakeUpload>()

    override fun maskTokensOnly(ocr: OcrFrame): MaskResult = maskTokens(ocr)

    override fun maskForUpload(frame: FrameImage, ocr: OcrFrame): MaskedUpload {
        uploads++
        val mask = maskTokens(ocr)
        return FakeUpload(ocr.copy(tokens = mask.maskedTokens), mask.plateHashes, this)
    }

    inner class FakeUpload(override val maskedOcr: OcrFrame, override val plateHashes: Set<String>, private val owner: FakeMasker) : MaskedUpload {
        override val jpeg: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        override val imageWidth: Int = maskedOcr.width
        override val imageHeight: Int = maskedOcr.height
        override val sha256: String = "f".repeat(64)
        override val maskedRects: List<Box> = emptyList()
        val cropped = mutableListOf<String>()

        override fun cropJpegs(crops: List<Pair<String, Box>>, maxTotalBytes: Int): List<RowCropJpeg> =
            crops.map { (id, _) -> cropped += id; RowCropJpeg(id, jpeg) }

        override fun close() {
            owner.closed += this
        }
    }
}

/** An [ApiClient] whose read-screen and reread answers are scripted; everything else is NotConfigured. */
class ScriptedApiClient : ApiClient {
    var readScreenResult: suspend (ReadScreenRequest) -> ApiResult<ReadScreenResponse> = { ApiResult.NotConfigured }
    var rereadResult: suspend (RereadRequest) -> ApiResult<RereadResponse> = { ApiResult.NotConfigured }
    val readRequests = mutableListOf<ReadScreenRequest>()
    val rereadRequests = mutableListOf<RereadRequest>()
    val budgets = mutableListOf<TapBudget>()

    override suspend fun readScreen(request: ReadScreenRequest, budget: TapBudget): ApiResult<ReadScreenResponse> {
        readRequests += request
        budgets += budget
        return readScreenResult(request)
    }

    override suspend fun reread(request: RereadRequest, budget: TapBudget): ApiResult<RereadResponse> {
        rereadRequests += request
        budgets += budget
        return rereadResult(request)
    }

    override suspend fun point(request: PointRequest): ApiResult<PointResponse> = ApiResult.NotConfigured
    override suspend fun parseNotification(request: ParseNotificationRequest, sessionId: String?): ApiResult<ParseNotificationResponse> =
        ApiResult.NotConfigured
    override suspend fun placePick(request: PlacePickRequest, sessionId: String?): ApiResult<PlacePickResponse> = ApiResult.NotConfigured
    override suspend fun event(report: SessionEventReport): ApiResult<Unit> = ApiResult.NotConfigured
    override suspend fun erase(): ApiResult<EraseResponse> = ApiResult.NotConfigured
    override suspend fun configCheck(): ApiResult<ConfigCheckResponse> = ApiResult.NotConfigured
    override suspend fun registerOwnerFcmToken(token: String): ApiResult<Unit> = ApiResult.NotConfigured
    override suspend fun ondcSearch(sessionId: String, pickup: LatLng, drop: LatLng): ApiResult<OndcSearchResponse> = ApiResult.NotConfigured
    override suspend fun ondcSelect(txnId: String, itemId: String): ApiResult<Unit> = ApiResult.NotConfigured
    override suspend fun ondcInit(txnId: String): ApiResult<Unit> = ApiResult.NotConfigured
    override suspend fun ondcConfirm(txnId: String, tapId: String): ApiResult<Unit> = ApiResult.NotConfigured
    override suspend fun ondcStatus(txnId: String): ApiResult<Unit> = ApiResult.NotConfigured
    override suspend fun ondcCancel(txnId: String, tapId: String, confirm: Boolean): ApiResult<Unit> = ApiResult.NotConfigured
    override suspend fun healthz(): ApiResult<Boolean> = ApiResult.NotConfigured
    override suspend fun readyz(): ApiResult<JsonObject> = ApiResult.NotConfigured
}
