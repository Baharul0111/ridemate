package app.ridemate.core.fare

import app.ridemate.core.config.FareParsersConfig
import app.ridemate.core.config.parseFareParsers
import app.ridemate.core.model.Provider
import app.ridemate.core.ocr.Box
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.core.ocr.OcrToken
import java.io.File
import kotlin.math.roundToInt

/** Builds OCR frames the way ML Kit reports them: words with pixel boxes, grouped into numbered lines. */
class FrameBuilder(private val width: Int = 1080, private val height: Int = 2400) {
    private val tokens = mutableListOf<OcrToken>()
    private var nextLine = 0

    /** One engine line of [words] starting at [left]; word widths follow the text length at ~0.55 × height. */
    fun line(left: Int, top: Int, h: Int, vararg words: String): FrameBuilder {
        val lineId = nextLine++
        var x = left
        for (w in words) {
            val wWidth = glyphWidth(w, h)
            tokens += OcrToken(w, Box(x, top, x + wWidth, top + h), lineId, blockId = 0)
            x += wWidth + (0.3 * h).roundToInt()
        }
        return this
    }

    /** One word whose right edge is at [right] (prices are right-aligned). */
    fun right(text: String, right: Int, top: Int, h: Int): FrameBuilder {
        tokens += OcrToken(text, Box(right - glyphWidth(text, h), top, right, top + h), nextLine++, blockId = 1)
        return this
    }

    fun token(text: String, box: Box): FrameBuilder {
        tokens += OcrToken(text, box, nextLine++, blockId = 2)
        return this
    }

    fun build(): OcrFrame = OcrFrame(width, height, tokens.toList())

    companion object {
        fun glyphWidth(text: String, h: Int): Int = (text.length * 0.55 * h).roundToInt().coerceAtLeast(1)
    }
}

/** A fare row as the demo screens draw it: label + payable price on the first line, ETA (+ struck price) below. */
data class RowSpec(
    val label: String,
    val price: String,
    val priceHeight: Int = 48,
    val eta: String? = "3 min away",
    val struck: String? = null,
    val struckHeight: Int = 32,
)

/** Rows start at y = 420 and are [pitch] px apart; the payable is right-aligned at x = 1000. */
fun fareScreen(rows: List<RowSpec>, header: String? = "Choose a ride", pitch: Int = 180): OcrFrame {
    val b = FrameBuilder()
    b.line(40, 20, 30, "10:42", "4G")
    if (header != null) b.line(60, 300, 44, *header.split(" ").toTypedArray())
    rows.forEachIndexed { i, row ->
        val y = 420 + i * pitch
        b.line(150, y, 40, *row.label.split(" ").toTypedArray())
        b.right(row.price, 1000, y - (row.priceHeight - 40) / 2, row.priceHeight)
        if (row.eta != null) b.line(150, y + 60, 30, *row.eta.split(" ").toTypedArray())
        if (row.struck != null) b.right(row.struck, 1000, y + 60, row.struckHeight)
    }
    return b.build()
}

object TestConfigs {
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

    /** One generic entry for every live and demo app (accuracy fallback when the repo has no bundled parsers). */
    val FARE_PARSERS_JSON: String = buildString {
        append("""{"schema": "1.0", "apps": {""")
        append(Provider.entries.filterNot { it.isNetwork }.joinToString(",") { "\"${it.packageName}\": [$ENTRY]" })
        append("}}")
    }

    val fareParsers: FareParsersConfig by lazy { parseFareParsers(FARE_PARSERS_JSON) }

    fun parser(app: Provider = Provider.DEMO_KESTREL): FareParser = FareParser.forApp(fareParsers, app, 1)!!

    /** The repo's own `config/remote_config/fare_parsers.json` (the APK default), or null outside the repo. */
    fun bundledFareParsers(): FareParsersConfig? {
        val root = File(System.getProperty("ridemate.repoRoot") ?: ".")
        return File(root, "config/remote_config/fare_parsers.json").takeIf { it.isFile }?.let { parseFareParsers(it.readText()) }
    }
}
