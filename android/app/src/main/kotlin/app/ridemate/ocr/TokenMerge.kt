package app.ridemate.ocr

import app.ridemate.core.ocr.Box
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.core.ocr.OcrToken
import kotlin.math.max
import kotlin.math.min

/**
 * Merges the Latin and Devanagari ML Kit reads of one frame into one token list.
 *
 * Both models see the whole frame, so most words come back twice. Two tokens are the same word when their boxes
 * overlap by at least [SAME_WORD_OVERLAP] of the smaller box. For each such pair one token survives:
 * - a token with Devanagari letters or digits wins over one without (the Latin model turns Hindi into noise);
 * - otherwise the Latin token wins (the Latin model reads Latin text and ₹ amounts best).
 *
 * Line and block ids stay the engine's own, with the Devanagari ids shifted past the Latin ones, so tokens from
 * different engines never share a line id (core [app.ridemate.core.ocr.Layout] regroups visual lines itself).
 */
object TokenMerge {
    const val SAME_WORD_OVERLAP = 0.5

    fun merge(width: Int, height: Int, latin: List<RecognizedElement>, devanagari: List<RecognizedElement>): OcrFrame {
        val latinClean = latin.filter { it.text.isNotBlank() }
        val devClean = devanagari.filter { it.text.isNotBlank() }
        val lineShift = (latinClean.maxOfOrNull { it.line } ?: -1) + 1
        val blockShift = (latinClean.maxOfOrNull { it.block } ?: -1) + 1

        val droppedLatin = BooleanArray(latinClean.size)
        val keptDev = mutableListOf<RecognizedElement>()
        for (dev in devClean) {
            val overlapping = latinClean.indices.filter { sameWord(latinClean[it].box, dev.box) }
            if (overlapping.isEmpty()) {
                keptDev += dev
                continue
            }
            val devWins = hasDevanagari(dev.text) && overlapping.none { hasDevanagari(latinClean[it].text) }
            if (devWins) {
                overlapping.forEach { droppedLatin[it] = true }
                keptDev += dev
            }
        }
        val tokens = latinClean.filterIndexed { i, _ -> !droppedLatin[i] }.map { OcrToken(it.text, clamp(it.box, width, height), it.line, it.block) } +
            keptDev.map { OcrToken(it.text, clamp(it.box, width, height), it.line + lineShift, it.block + blockShift) }
        return OcrFrame(width, height, tokens.filter { it.box.width > 0 && it.box.height > 0 }, engine = ENGINE)
    }

    /** Overlap of the two boxes as a share of the smaller box's area. */
    fun overlapOfSmaller(a: Box, b: Box): Double {
        val ix = max(0, min(a.r, b.r) - max(a.l, b.l)).toLong()
        val iy = max(0, min(a.b, b.b) - max(a.t, b.t)).toLong()
        val smaller = min(a.width.toLong() * a.height, b.width.toLong() * b.height)
        if (smaller <= 0) return 0.0
        return (ix * iy).toDouble() / smaller
    }

    private fun sameWord(a: Box, b: Box): Boolean = overlapOfSmaller(a, b) >= SAME_WORD_OVERLAP

    /** Devanagari block U+0900–U+097F (letters, signs and digits ०–९). */
    fun hasDevanagari(text: String): Boolean = text.any { it in 'ऀ'..'ॿ' }

    private fun clamp(box: Box, width: Int, height: Int): Box {
        val l = box.l.coerceIn(0, width)
        val t = box.t.coerceIn(0, height)
        return Box(l, t, box.r.coerceIn(l, width), box.b.coerceIn(t, height))
    }

    const val ENGINE = "mlkit-text-v2"
}
