package app.ridemate.core.ocr

import kotlin.math.max
import kotlin.math.min

/** One engine text line: token ids sorted left to right, and their union box. */
data class TextLine(val tokenIds: List<Int>, val box: Box)

/**
 * The frame's text as one string ("tok tok\ntok"), with a map from each character back to the token it came from.
 * Tokens on a line are joined by one space and lines by `\n`, so offsets are stable and spans map to tokens.
 */
class TextDocument internal constructor(
    val text: String,
    private val owner: IntArray,
    private val starts: Map<Int, Int>,
) {
    /** Token ids that own at least one character in `[start, end)`. */
    fun tokensIn(start: Int, end: Int): List<Int> =
        (max(0, start) until min(end, owner.size)).map { owner[it] }.filter { it >= 0 }.distinct()

    /** Offset of the token's first character in [text], or null if the token is not in the document. */
    fun startOf(tokenId: Int): Int? = starts[tokenId]
}

object Layout {
    private const val SAME_LINE_OVERLAP = 0.5
    private const val SAME_LINE_MAX_GAP_HEIGHTS = 2.5

    /**
     * Lines as the engine reported them, keyed by (block, line). Tokens without a line id are clustered into lines
     * by vertical overlap and horizontal proximity. Blank tokens are skipped. Sorted top to bottom, then left.
     */
    fun textLines(frame: OcrFrame): List<TextLine> {
        val keyed = LinkedHashMap<Pair<Int, Int>, MutableList<Int>>()
        val loose = mutableListOf<MutableList<Int>>()
        val ordered = frame.tokens.indices.filter { frame.tokens[it].text.isNotBlank() }
            .sortedWith(compareBy({ frame.tokens[it].box.centerY }, { frame.tokens[it].box.l }))
        for (id in ordered) {
            val token = frame.tokens[id]
            if (token.lineId >= 0) {
                keyed.getOrPut(token.blockId to token.lineId) { mutableListOf() } += id
                continue
            }
            val home = loose.firstOrNull { line -> line.any { sameLine(frame.tokens[it].box, token.box) } }
            if (home != null) home += id else loose += mutableListOf(id)
        }
        return (keyed.values + loose).map { ids ->
            val sorted = ids.sortedWith(compareBy({ frame.tokens[it].box.l }, { frame.tokens[it].box.t }))
            TextLine(sorted, Box.unionOf(sorted.map { frame.tokens[it].box }))
        }.sortedWith(compareBy({ it.box.t }, { it.box.l }))
    }

    fun document(frame: OcrFrame, lines: List<TextLine> = textLines(frame)): TextDocument {
        val text = StringBuilder()
        val owner = ArrayList<Int>()
        val starts = HashMap<Int, Int>()
        lines.forEachIndexed { li, line ->
            if (li > 0) {
                text.append('\n'); owner += -1
            }
            line.tokenIds.forEachIndexed { ti, id ->
                if (ti > 0) {
                    text.append(' '); owner += -1
                }
                starts[id] = text.length
                val t = frame.tokens[id].text
                text.append(t)
                repeat(t.length) { owner += id }
            }
        }
        return TextDocument(text.toString(), owner.toIntArray(), starts)
    }

    fun lineText(frame: OcrFrame, line: TextLine): String = line.tokenIds.joinToString(" ") { frame.tokens[it].text }

    private fun sameLine(a: Box, b: Box): Boolean {
        val minH = min(a.height, b.height).coerceAtLeast(1)
        if (a.verticalOverlap(b) < SAME_LINE_OVERLAP * minH) return false
        val gap = max(a.l, b.l) - min(a.r, b.r)
        return gap <= SAME_LINE_MAX_GAP_HEIGHTS * max(a.height, b.height)
    }
}
