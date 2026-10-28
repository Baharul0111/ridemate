package app.ridemate.ui

import app.ridemate.core.format.IndianFormat
import app.ridemate.core.format.IstTime
import app.ridemate.core.format.Numerals
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Display formatting for the app shell, on top of core `format` (goal 26): rupees with Indian grouping, times in
 * India Standard Time on a 12-hour clock, whatever the phone's own zone. Digits are international (Latin) in both
 * English and Hindi, the form Indian official usage prescribes, so prices read the same in both languages.
 */
object UiFormat {
    val NUMERALS: Numerals = Numerals.LATIN

    /** `₹1,23,456`. */
    fun money(rupees: Long): String = IndianFormat.rupees(rupees, NUMERALS)

    /** `₹110–130`, or `₹120` when both ends match. */
    fun moneyRange(min: Int, max: Int): String = IndianFormat.rupeeRange(minOf(min, max)..maxOf(min, max), NUMERALS)

    /** `1:30 PM` (IST). */
    fun timeIst(epochMs: Long): String = IstTime.resumeTime(epochMs, NUMERALS)

    /** `1:05:09 PM` (IST), the "seen at" stamp. */
    fun timeIstWithSeconds(epochMs: Long): String = IstTime.seenAt(epochMs, NUMERALS)

    /** The server's `resume_at_ist` ("13:30") as `1:30 PM`; null when absent or malformed. */
    fun resumeAtIst(hhmm: String?): String? = IstTime.resumeAtIst(hhmm, NUMERALS)

    /** `28 Oct 2026` (IST calendar date). */
    fun dateIst(epochMs: Long, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(Instant.ofEpochMilli(epochMs).atZone(IstTime.ZONE))

    /** `28 Oct 2026, 1:30 PM` (IST). */
    fun dateTimeIst(epochMs: Long, locale: Locale = Locale.getDefault()): String = "${dateIst(epochMs, locale)}, ${timeIst(epochMs)}"

    /** A `YYYY-MM-DD` date (e.g. the budget horizon) as `26 Jan 2027`; the input itself when it is not a date. */
    fun isoDate(text: String?, locale: Locale = Locale.getDefault()): String? {
        if (text.isNullOrBlank()) return null
        return runCatching { DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(LocalDate.parse(text.trim())) }.getOrDefault(text)
    }

    /** US dollars with cents (Modal credit is in USD): `$1.23`. */
    fun usd(value: Double): String = String.format(Locale.ROOT, "$%,.2f", value)

    /** Whole percent of [part] in [whole], clamped to 0..999; null when [whole] is not positive. */
    fun percent(part: Double, whole: Double): Int? =
        if (whole <= 0.0) null else ((part / whole) * 100).toInt().coerceIn(0, 999)
}
