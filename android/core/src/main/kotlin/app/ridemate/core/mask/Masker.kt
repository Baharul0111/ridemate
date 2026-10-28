package app.ridemate.core.mask

import app.ridemate.core.ocr.Box
import app.ridemate.core.ocr.Layout
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.core.ocr.OcrToken
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Text that replaces every masked span (also the marker §4.5.9 normalisation uses). */
const val MASK_MARKER: String = "<m>"

/**
 * What to blur before a frame leaves the phone.
 * - [rectsToBlur]: pixel rects (status bar band first), including partial-token slices.
 * - [maskedTokens]: the frame's tokens with every PII span replaced by [MASK_MARKER] (safe to log or export).
 * - [plateHashes]: SHA-256 of each normalised plate seen, for same-driver detection; the raw plate is never kept.
 */
data class MaskResult(
    val rectsToBlur: List<Box>,
    val maskedTokens: List<OcrToken>,
    val maskedTokenIds: Set<Int>,
    val plateHashes: Set<String>,
    val kinds: Set<PiiKind>,
)

/** Detects PII over the whole frame's text (so codes split across tokens or lines are found) and maps it to boxes. */
fun maskTokens(frame: OcrFrame, cfg: MaskConfig = MaskConfig.DEFAULT): MaskResult {
    val band = ceil(frame.height * cfg.statusBarFraction).toInt().coerceAtMost(frame.height)
    val doc = Layout.document(frame)
    val spans = PiiDetector(cfg).detect(doc.text)
    val covered = HashMap<Int, MutableList<IntRange>>()
    for (span in mergeSpans(spans)) {
        for (id in doc.tokensIn(span.start, span.end)) {
            val start = doc.startOf(id) ?: continue
            val len = frame.tokens[id].text.length
            val a = max(span.start - start, 0)
            val b = min(span.end - start, len)
            if (a < b) covered.getOrPut(id) { mutableListOf() } += a until b
        }
    }
    val kinds = spans.mapTo(mutableSetOf()) { it.kind }
    val rects = mutableListOf<Box>()
    if (band > 0) {
        rects += Box(0, 0, frame.width, band)
        kinds += PiiKind.STATUS_BAR
    }
    frame.tokens.forEachIndexed { id, token ->
        if (token.text.isNotEmpty() && token.box.centerY < band) covered[id] = mutableListOf(token.text.indices)
    }
    val masked = frame.tokens.mapIndexed { id, token ->
        val ranges = covered[id]?.let(::mergeRanges) ?: return@mapIndexed token
        ranges.forEach { rects += sliceRect(frame, token, it) }
        token.copy(text = replaceRanges(token.text, ranges))
    }
    val plates = spans.mapNotNullTo(mutableSetOf()) { span -> span.plate?.let(PlateHash::sha256) }
    return MaskResult(rects, masked, covered.keys.toSet(), plates, kinds)
}

/** Notification text with every PII span replaced by [MASK_MARKER]. */
fun maskText(text: String, cfg: MaskConfig = MaskConfig.DEFAULT): String {
    val spans = mergeSpans(PiiDetector(cfg).detect(text))
    if (spans.isEmpty()) return text
    val out = StringBuilder()
    var at = 0
    for (span in spans) {
        out.append(text, at, span.start).append(MASK_MARKER)
        at = span.end
    }
    return out.append(text, at, text.length).toString()
}

/** Plate hashes for same-driver detection (§4.7): SHA-256 (lower-case hex) of the canonical plate. */
object PlateHash {
    /** The hash of the first plate found in [text] (any spacing, dashes or Devanagari digits), or null. */
    fun of(text: String): String? =
        PiiDetector().detect(text).firstOrNull { it.kind == PiiKind.PLATE }?.plate?.let(::sha256)

    fun sha256(canonicalPlate: String): String =
        MessageDigest.getInstance("SHA-256").digest(canonicalPlate.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
    val out = mutableListOf<IntRange>()
    for (r in ranges.sortedBy { it.first }) {
        val last = out.lastOrNull()
        if (last != null && r.first <= last.last + 1) out[out.lastIndex] = last.first..max(last.last, r.last) else out += r
    }
    return out
}

private fun replaceRanges(text: String, ranges: List<IntRange>): String {
    val out = StringBuilder()
    var at = 0
    for (r in ranges) {
        out.append(text, at, r.first).append(MASK_MARKER)
        at = r.last + 1
    }
    return out.append(text, at, text.length).toString()
}

/**
 * The part of the token's box under chars [range], assuming evenly spaced glyphs, padded by half a glyph
 * horizontally and 10% vertically so anti-aliased edges are blurred too. A whole-token range gives the padded box.
 */
private fun sliceRect(frame: OcrFrame, token: OcrToken, range: IntRange): Box {
    val box = token.box
    val len = token.text.length.coerceAtLeast(1)
    val glyph = box.width.toDouble() / len
    val padX = ceil(glyph / 2).toInt()
    val padY = max(1, box.height / 10)
    val left = if (range.first == 0) box.l else floor(box.l + glyph * range.first).toInt()
    val right = if (range.last >= len - 1) box.r else ceil(box.l + glyph * (range.last + 1)).toInt()
    return Box(left, box.t, max(left, right), box.b).expand(padX, padY, frame.width, frame.height)
}
