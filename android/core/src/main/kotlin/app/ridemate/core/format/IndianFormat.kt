package app.ridemate.core.format

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Indian digit grouping for display: the last three digits, then pairs (`1,23,45,678`). */
object IndianFormat {
    fun group(value: Long, numerals: Numerals = Numerals.LATIN): String {
        val digits = if (value < 0) (-value).toString() else value.toString()
        val grouped = if (digits.length <= 3) {
            digits
        } else {
            val head = digits.dropLast(3)
            val pairs = head.reversed().chunked(2).joinToString(",").reversed()
            "$pairs,${digits.takeLast(3)}"
        }
        return Digits.write(if (value < 0) "-$grouped" else grouped, numerals)
    }

    /** `₹1,23,456`. */
    fun rupees(value: Long, numerals: Numerals = Numerals.LATIN): String = "₹" + group(value, numerals)

    /** `₹110–130` for a range, `₹120` when both ends are equal. */
    fun rupeeRange(range: IntRange, numerals: Numerals = Numerals.LATIN): String =
        if (range.first == range.last) {
            rupees(range.first.toLong(), numerals)
        } else {
            rupees(range.first.toLong(), numerals) + "–" + group(range.last.toLong(), numerals)
        }
}

/**
 * Times shown to the user are always India Standard Time, whatever zone the phone is set to (M5). The AM/PM
 * marker is always `AM`/`PM`; only the digits follow [Numerals].
 */
object IstTime {
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    private val SEEN_AT = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ENGLISH)
    private val RESUME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val SERVER_HH_MM = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT)

    /** "seen at" stamp, `h:mm:ss a` (e.g. `1:05:09 PM`). */
    fun seenAt(epochMs: Long, numerals: Numerals = Numerals.LATIN): String =
        Digits.write(SEEN_AT.format(Instant.ofEpochMilli(epochMs).atZone(ZONE)), numerals)

    /** Resume time, `h:mm a` (e.g. `1:30 PM`). */
    fun resumeTime(epochMs: Long, numerals: Numerals = Numerals.LATIN): String =
        Digits.write(RESUME.format(Instant.ofEpochMilli(epochMs).atZone(ZONE)), numerals)

    /** The server's `resume_at_ist` (`"13:30"`, already IST) as `1:30 PM`; null when malformed. */
    fun resumeAtIst(hhmm: String?, numerals: Numerals = Numerals.LATIN): String? {
        val time = runCatching { LocalTime.parse(hhmm?.trim().orEmpty(), SERVER_HH_MM) }.getOrNull() ?: return null
        return Digits.write(RESUME.format(time), numerals)
    }
}
