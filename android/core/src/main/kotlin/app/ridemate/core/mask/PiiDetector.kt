package app.ridemate.core.mask

import app.ridemate.core.format.Digits
import app.ridemate.core.format.normalizeSpaces

enum class PiiKind { PHONE, OTP, PLATE, NAME, EMAIL, STATUS_BAR }

/** PII at `[start, end)` of a text. [plate] is the canonical plate (e.g. `AS01AB1234`) for PLATE spans. */
data class PiiSpan(val start: Int, val end: Int, val kind: PiiKind, val plate: String? = null) {
    init {
        require(start in 0 until end) { "empty span" }
    }
}

/**
 * Finds phone numbers, OTP/PIN codes, number plates, driver names and emails in text. Detection runs on a
 * same-length normalisation (Devanagari digits → ASCII, exotic spaces → space), so spans index the original text.
 * Prices are never reported: digits directly after ₹/Rs/INR are not an OTP, and phone numbers cannot follow ₹.
 */
class PiiDetector(private val cfg: MaskConfig = MaskConfig.DEFAULT) {
    // Null for an empty anchor list: an empty alternation would match everywhere and turn every number into an OTP.
    private val otpAnchors: String? = alternation(cfg.otpAnchors)
    private val otpAfter: Regex? = otpAnchors?.let { otp ->
        Regex("$NOT_LETTER_BEHIND(?:$otp)$NOT_LETTER_AHEAD[^\\d]{0,30}?(\\d(?:[ -]?\\d){3,5})(?!\\d)", RegexOption.IGNORE_CASE)
    }
    private val otpBefore: Regex? = otpAnchors?.let { otp ->
        Regex("(?<!\\d)(\\d(?:[ -]?\\d){3,5})(?!\\d)[^\\d]{0,25}?$NOT_LETTER_BEHIND(?:$otp)$NOT_LETTER_AHEAD", RegexOption.IGNORE_CASE)
    }
    private val nameAnchor: Regex? = anchorRegex(cfg.nameAnchors)
    private val trailingNameAnchor: Regex? = anchorRegex(cfg.trailingNameAnchors)

    /** Every span found, sorted by start; spans may overlap (use [mergeSpans] before replacing text). */
    fun detect(text: String): List<PiiSpan> {
        val s = Digits.toAscii(normalizeSpaces(text))
        val spans = mutableListOf<PiiSpan>()
        EMAIL.findAll(s).forEach { spans += PiiSpan(it.range.first, it.range.last + 1, PiiKind.EMAIL) }
        MOBILE.findAll(s).forEach { spans += PiiSpan(it.range.first, it.range.last + 1, PiiKind.PHONE) }
        LANDLINE.findAll(s).filter { m -> m.value.count { it.isDigit() } == 11 }
            .forEach { spans += PiiSpan(it.range.first, it.range.last + 1, PiiKind.PHONE) }
        plates(s, spans)
        for (regex in listOfNotNull(otpAfter, otpBefore)) {
            regex.findAll(s).forEach { m ->
                val g = m.groups[1]!!.range
                if (!followsCurrency(s, g.first)) spans += PiiSpan(g.first, g.last + 1, PiiKind.OTP)
            }
        }
        names(s, spans)
        namesBefore(s, spans)
        return spans.sortedWith(compareBy({ it.start }, { it.end }))
    }

    private fun plates(s: String, spans: MutableList<PiiSpan>) {
        PLATE.findAll(s).forEach { m ->
            val (state, rto, seriesRaw, number) = m.destructured
            val series = seriesRaw.filter { it.isLetter() }
            if (series.isEmpty() && number.length != 4) return@forEach
            val canonical = state + rto.padStart(2, '0') + series + number.padStart(4, '0')
            spans += PiiSpan(m.range.first, m.range.last + 1, PiiKind.PLATE, canonical)
        }
        BH_PLATE.findAll(s).forEach { m ->
            val (year, number, series) = m.destructured
            spans += PiiSpan(m.range.first, m.range.last + 1, PiiKind.PLATE, "${year}BH$number$series")
        }
    }

    private fun names(s: String, spans: MutableList<PiiSpan>) {
        for (anchor in nameAnchor?.findAll(s).orEmpty()) {
            var i = anchor.range.last + 1
            var newlines = 0
            while (i < s.length && s[i] in SEPARATORS) {
                if (s[i] == '\n' && ++newlines > 1) break
                i++
            }
            var first = -1
            var last = -1
            var words = 0
            while (i < s.length && words < MAX_NAME_WORDS) {
                val end = wordEnd(s, i)
                if (end == i || s.substring(i, end).lowercase().trimEnd('.') in cfg.nameStopwords) break
                if (first < 0) first = i
                last = end
                words++
                if (end + 1 < s.length && s[end] == ' ' && wordEnd(s, end + 1) > end + 1) i = end + 1 else break
            }
            if (first >= 0) spans += PiiSpan(first, last, PiiKind.NAME)
        }
    }

    /** Up to [MAX_NAME_WORDS] name words directly before a trailing anchor ("Ramesh Kumar is on the way"). */
    private fun namesBefore(s: String, spans: MutableList<PiiSpan>) {
        for (anchor in trailingNameAnchor?.findAll(s).orEmpty()) {
            var i = anchor.range.first
            while (i > 0 && s[i - 1] in " ,") i--
            var first = -1
            var last = -1
            var words = 0
            while (i > 0 && words < MAX_NAME_WORDS) {
                val start = wordStart(s, i)
                if (start == i || s.substring(start, i).lowercase().trimEnd('.') in cfg.nameStopwords) break
                if (last < 0) last = i
                first = start
                words++
                if (start >= 2 && s[start - 1] == ' ' && wordStart(s, start - 1) < start - 1) i = start - 1 else break
            }
            if (first >= 0) spans += PiiSpan(first, last, PiiKind.NAME)
        }
    }

    /** Start of a capitalised Latin word or a Devanagari word ending at [end]; [end] itself when there is none. */
    private fun wordStart(s: String, end: Int): Int {
        if (end == 0) return end
        var j = end
        if (isDevanagariLetter(s[end - 1])) {
            while (j > 0 && isDevanagariLetter(s[j - 1])) j--
            return j
        }
        while (j > 0 && (s[j - 1].isLetter() && s[j - 1].code < 0x0900 || s[j - 1] in "'’.")) j--
        while (j < end && s[j] in "'’.") j++
        return if (j < end && s[j] in 'A'..'Z') j else end
    }

    /** End of a capitalised Latin word or a Devanagari word starting at [i]; [i] itself when there is none. */
    private fun wordEnd(s: String, i: Int): Int {
        var j = i
        if (s[i] in 'A'..'Z') {
            while (j < s.length && (s[j].isLetter() && s[j].code < 0x0900 || s[j] in "'’.")) j++
        } else if (isDevanagariLetter(s[i])) {
            while (j < s.length && isDevanagariLetter(s[j])) j++
        }
        return j
    }

    private fun isDevanagariLetter(c: Char): Boolean = c in 'ऀ'..'ॣ' || c in '॰'..'ॿ'

    private fun followsCurrency(s: String, digitStart: Int): Boolean {
        val before = s.substring(0, digitStart).trimEnd().lowercase()
        return before.endsWith("₹") || before.endsWith("rs") || before.endsWith("rs.") || before.endsWith("inr")
    }

    companion object {
        private const val MAX_NAME_WORDS = 3
        private const val SEPARATORS = " ,:-–\n"
        private const val NOT_LETTER_BEHIND = "(?<![\\p{L}\\p{M}])"
        private const val NOT_LETTER_AHEAD = "(?![\\p{L}\\p{M}])"
        private const val STATES =
            "AN|AP|AR|AS|BR|CG|CH|CT|DD|DL|DN|GA|GJ|HP|HR|JH|JK|KA|KL|LA|LD|MH|ML|MN|MP|MZ|NL|OD|OR|PB|PY|RJ|SK|TN|TR|TS|UA|UK|UP|WB"

        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")

        /** Indian mobile: optional +91 / 0091 / 91 / 0, then ten digits starting 6–9, spaced or dashed anywhere. */
        private val MOBILE = Regex("(?<![\\d₹.,/])(?:(?:\\+\\s?|00)?91[\\s-]?|0)?[6-9](?:[\\s-]?\\d){9}(?!\\d)")

        /** Landline with STD code (`0361-2345678`); kept only when it has exactly 11 digits. */
        private val LANDLINE = Regex("(?<![\\d₹.,/])0\\d{2,4}[\\s-]?\\d{6,8}(?!\\d)")

        /** State code, RTO (1–2 digits), series (0–3 letters, Delhi-style `3C AB` too), number (1–4 digits). */
        private val PLATE = Regex(
            "(?<![A-Za-z0-9])($STATES)[\\s.-]?(\\d{1,2})[\\s.-]?([A-Z]{1,3}(?:[\\s.-]?[A-Z]{1,2})?)?[\\s.-]?(\\d{1,4})(?![A-Za-z0-9])",
        )

        /** Bharat series: `22 BH 1234 AA`. */
        private val BH_PLATE = Regex("(?<![A-Za-z0-9])(\\d{2})[\\s.-]?BH[\\s.-]?(\\d{4})[\\s.-]?([A-HJ-NP-Z]{1,2})(?![A-Za-z0-9])")

        /** The phrases as one regex alternation (longest first, any whitespace between words); null when there are none. */
        private fun alternation(words: List<String>): String? = words.filter { it.isNotBlank() }.sortedByDescending { it.length }
            .joinToString("|") { phrase -> phrase.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) } }
            .ifEmpty { null }

        private fun anchorRegex(words: List<String>): Regex? = alternation(words)?.let {
            Regex("$NOT_LETTER_BEHIND(?:$it)$NOT_LETTER_AHEAD", RegexOption.IGNORE_CASE)
        }
    }
}

/** Sorts and merges overlapping or touching spans; the merged span keeps the first span's kind. */
fun mergeSpans(spans: List<PiiSpan>): List<PiiSpan> {
    val out = mutableListOf<PiiSpan>()
    for (span in spans.sortedBy { it.start }) {
        val last = out.lastOrNull()
        if (last != null && span.start <= last.end) {
            out[out.lastIndex] = last.copy(end = maxOf(last.end, span.end))
        } else {
            out += span
        }
    }
    return out
}
