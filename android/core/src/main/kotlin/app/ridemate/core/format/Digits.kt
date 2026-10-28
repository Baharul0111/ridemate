package app.ridemate.core.format

/** Which digits user-facing numbers are written with (English or Hindi UI). */
enum class Numerals { LATIN, DEVANAGARI }

/**
 * Digit-script helpers. Every mapping is one UTF-16 char to one char, so offsets found in a normalised string
 * are valid in the original (the parser and the masker rely on this).
 */
object Digits {
    private const val DEVANAGARI_ZERO = '०'
    private const val BENGALI_ZERO = '০'

    /** Devanagari (०–९) and Bengali/Assamese (০–৯) digits → ASCII; everything else unchanged. */
    fun toAscii(text: String): String {
        if (text.none { isNativeDigit(it) }) return text
        val out = CharArray(text.length)
        for (i in text.indices) out[i] = asciiOf(text[i])
        return String(out)
    }

    fun isNativeDigit(c: Char): Boolean = c in DEVANAGARI_ZERO..'९' || c in BENGALI_ZERO..'৯'

    /** True for ASCII, Devanagari or Bengali digits. */
    fun isAnyDigit(c: Char): Boolean = c in '0'..'9' || isNativeDigit(c)

    fun write(text: String, numerals: Numerals): String = when (numerals) {
        Numerals.LATIN -> text
        Numerals.DEVANAGARI -> buildString(text.length) {
            for (c in text) append(if (c in '0'..'9') DEVANAGARI_ZERO + (c - '0') else c)
        }
    }

    private fun asciiOf(c: Char): Char = when (c) {
        in DEVANAGARI_ZERO..'९' -> '0' + (c - DEVANAGARI_ZERO)
        in BENGALI_ZERO..'৯' -> '0' + (c - BENGALI_ZERO)
        else -> c
    }
}

/** Replaces exotic spaces OCR and apps emit (NBSP, thin and narrow spaces) with a plain space; same length. */
internal fun normalizeSpaces(text: String): String =
    text.map { if (it == ' ' || it == ' ' || it == ' ' || it == ' ' || it == '\t') ' ' else it }
        .joinToString("")
