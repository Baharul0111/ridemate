package app.ridemate.core.format

/** How a rupee amount was read; the gate and logs use these, the UI never shows them. */
enum class AmountFlag {
    /** Devanagari or Bengali digits were converted. */
    NATIVE_DIGITS,

    /** A leading `Z`, `R` or `%` directly before a digit was read as ₹ (M5); in the price column also `{ [ ( < T`. */
    OCR_CURRENCY_CONFUSION,

    /** A leading `2` (or, in the price column, `7`) was read as ₹ because the caller's layout context said so (never on text alone). */
    CURRENCY_FROM_LEADING_TWO,

    /** Paise were rounded half-up to whole rupees (see [RupeeParser]). */
    ROUNDED_DECIMAL,

    /** Lakh grouping (`1,23,456`) that is not also valid western grouping. */
    INDIAN_GROUPING,

    /** A bare number with no ₹/Rs/INR marker, accepted only because the caller asked for it. */
    NO_CURRENCY_MARKER,

    RANGE,
}

/** What the caller knows about the token's position that the text alone cannot say. */
enum class CurrencyContext {
    NONE,

    /**
     * The token sits in the price column at price height while its row has no ₹ token, so a leading `2` is
     * ML Kit's misread of ₹ (`₹110` → `2110`). Only the fare parser sets this, from row geometry.
     */
    LEADING_TWO_IS_RUPEE,

    /**
     * The token sits in the price column of a fare row that has no ₹ token: the phone's ML Kit dropped the ₹ glyph
     * (`₹176` → `176`) or read it as another glyph (`₹264` → `{264`, `₹272` → `T272`, `₹289` → `7289`), as recorded
     * in `eval/recorded/ocr`. A leading `{`, `[`, `(`, `<` or `T` before a digit is the ₹; so is a leading `2` or `7`
     * on a comma-less amount of 4+ digits (Indian apps group amounts from 1,000 up); otherwise the bare number is
     * the amount ([AmountFlag.NO_CURRENCY_MARKER]). Only the fare parser sets this, from row geometry, and the gate
     * still needs Channel A's independent read to agree before any number is shown.
     */
    PRICE_COLUMN,
}

/** A parsed rupee amount in whole rupees; [min] == [max] unless the screen showed a range. */
data class RupeeAmount(val min: Int, val max: Int, val raw: String, val flags: Set<AmountFlag>) {
    init {
        require(min in 0..max) { "invalid amount range" }
    }

    val isRange: Boolean get() = min != max
    val range: IntRange get() = min..max
}

/**
 * Strict parser for Indian rupee amounts: `₹1,234`, `Rs 1234`, `Rs. 1234`, `INR 1,234`, `₹110–130`, `₹110 - ₹130`,
 * `₹ 1,23,456`, `₹१२०`, `₹110/-`. It returns null rather than guessing: malformed grouping (`1,2345`, `1,23`),
 * a reversed range, stray characters or more than nine integer digits all give null.
 *
 * Decimal rule: paise are rounded half-up to the nearest rupee (`₹99.50` → 100, `₹99.49` → 99), the "rounding
 * off" convention Indian invoices and the CGST Act (s.170) use; the result carries [AmountFlag.ROUNDED_DECIMAL].
 */
object RupeeParser {
    private const val MARKER = "(?:₹|(?i:rs)\\.?|(?i:inr)|रु\\.?|रू\\.?)"
    private const val NUMBER = "(\\d[\\d,]*(?:\\.\\d{1,2})?)"
    private const val DASH = "(?:-|–|—|−|(?i:to))"
    private val AMOUNT = Regex(
        "^($MARKER\\s*)?$NUMBER(?:\\s*$DASH\\s*(?:$MARKER\\s*)?$NUMBER)?(?:\\s*/\\s*[-–])?$",
    )
    private val PLAIN = Regex("^\\d+$")
    private val INDIAN = Regex("^\\d{1,2}(?:,\\d{2})*,\\d{3}$")
    private val WESTERN = Regex("^\\d{1,3}(?:,\\d{3})+$")
    private val CONFUSED_MARKER = Regex("^[ZzR%](?=\\d)")
    private val LEADING_TWO = Regex("^2(?=\\d)")
    private val COLUMN_GLYPH = Regex("^[{\\[(<T](?=\\d)")
    private val COLUMN_LEADING_DIGIT = Regex("^[27](?=\\d{3,}(?:\\.\\d{1,2})?(?:\\s*(?:-|–|—|−).*)?$)")
    private const val MAX_INT_DIGITS = 9

    fun parse(
        text: String,
        context: CurrencyContext = CurrencyContext.NONE,
        requireCurrency: Boolean = true,
    ): RupeeAmount? {
        val raw = text.trim()
        val flags = mutableSetOf<AmountFlag>()
        var s = normalizeSpaces(raw)
        val ascii = Digits.toAscii(s)
        if (ascii != s) flags += AmountFlag.NATIVE_DIGITS
        s = ascii
        if (CONFUSED_MARKER.containsMatchIn(s)) {
            s = "₹" + s.substring(1)
            flags += AmountFlag.OCR_CURRENCY_CONFUSION
        } else if (context == CurrencyContext.LEADING_TWO_IS_RUPEE && LEADING_TWO.containsMatchIn(s)) {
            s = "₹" + s.substring(1)
            flags += AmountFlag.CURRENCY_FROM_LEADING_TWO
        } else if (context == CurrencyContext.PRICE_COLUMN && COLUMN_GLYPH.containsMatchIn(s)) {
            s = "₹" + s.substring(1)
            flags += AmountFlag.OCR_CURRENCY_CONFUSION
        } else if (context == CurrencyContext.PRICE_COLUMN && COLUMN_LEADING_DIGIT.containsMatchIn(s)) {
            s = "₹" + s.substring(1)
            flags += AmountFlag.CURRENCY_FROM_LEADING_TWO
        }
        val m = AMOUNT.matchEntire(s) ?: return null
        if (m.groups[1] == null) {
            if (requireCurrency && context != CurrencyContext.PRICE_COLUMN) return null
            flags += AmountFlag.NO_CURRENCY_MARKER
        }
        val first = number(m.groupValues[2], flags) ?: return null
        val secondText = m.groups[3]?.value
        if (secondText == null) return RupeeAmount(first, first, raw, flags)
        val second = number(secondText, flags) ?: return null
        if (second < first) return null
        if (second > first) flags += AmountFlag.RANGE
        return RupeeAmount(first, second, raw, flags)
    }

    /** A bare number such as `1,23,456` or `१२०` (no currency needed), rounded per the decimal rule. */
    fun parseNumber(text: String): Int? = parse(text, requireCurrency = false)?.takeIf { !it.isRange }?.min

    /** True when [text] starts with a Z/R/% that OCR made of a ₹ directly before a digit. */
    fun hasCurrencyConfusion(text: String): Boolean = CONFUSED_MARKER.containsMatchIn(Digits.toAscii(text.trim()))

    /** Replaces a leading Z/R/% confusion with ₹; same length, so offsets stay valid. */
    fun fixCurrencyConfusion(text: String): String {
        val lead = text.length - text.trimStart().length
        val rest = text.substring(lead)
        return if (CONFUSED_MARKER.containsMatchIn(Digits.toAscii(rest))) text.substring(0, lead) + "₹" + rest.substring(1) else text
    }

    private fun number(text: String, flags: MutableSet<AmountFlag>): Int? {
        val intPart = text.substringBefore('.')
        val fraction = if ('.' in text) text.substringAfter('.') else ""
        when {
            PLAIN.matches(intPart) -> Unit
            WESTERN.matches(intPart) -> Unit
            INDIAN.matches(intPart) -> flags += AmountFlag.INDIAN_GROUPING
            else -> return null
        }
        val digits = intPart.replace(",", "")
        if (digits.length > MAX_INT_DIGITS || (digits.length > 1 && digits[0] == '0')) return null
        var value = digits.toLong()
        if (fraction.isNotEmpty()) {
            val paise = fraction.padEnd(2, '0').toInt()
            if (paise != 0) flags += AmountFlag.ROUNDED_DECIMAL
            if (paise >= 50) value += 1
        }
        return if (value <= Int.MAX_VALUE) value.toInt() else null
    }
}
