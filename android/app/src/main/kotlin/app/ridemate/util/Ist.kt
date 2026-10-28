package app.ridemate.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Indian Standard Time helpers (goal 26: times are shown in Indian time, 12-hour). Everything is java.time with
 * `Asia/Kolkata`, independent of the phone's own time zone.
 */
object Ist {
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    private val HM_24: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun zoned(epochMs: Long): ZonedDateTime = Instant.ofEpochMilli(epochMs).atZone(ZONE)

    /** The IST calendar date of an instant (quota days and "today" labels are IST days). */
    fun date(epochMs: Long): LocalDate = zoned(epochMs).toLocalDate()

    /** "1:30 pm" (12-hour, no leading zero). Hindi uses its own day-period words. */
    fun time12h(epochMs: Long, locale: Locale = Locale.getDefault()): String =
        format12h(zoned(epochMs).toLocalTime(), locale)

    /** "1:30:05 pm" — the "seen in Uber at …" freshness label (§4.3.6). */
    fun time12hWithSeconds(epochMs: Long, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("h:mm:ss a", locale).format(zoned(epochMs)).lowerAsciiDayPeriod(locale)

    /** "13:30" (24-hour), the wire format of `resume_at_ist`. */
    fun time24h(epochMs: Long): String = HM_24.format(zoned(epochMs))

    /** "28 Oct 2026, 1:30 pm". */
    fun dateTime(epochMs: Long, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", locale).format(zoned(epochMs)).lowerAsciiDayPeriod(locale)

    /** Parses the server's `resume_at_ist` ("13:30"); null when absent or malformed. */
    fun parseResumeAt(hhmm: String?): LocalTime? {
        if (hhmm.isNullOrBlank()) return null
        return try {
            LocalTime.parse(hhmm.trim(), HM_24)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** "13:30" → "1:30 pm" for "AI reading resumes at 1:30 pm (IST)"; null when malformed. */
    fun formatResumeAt(hhmm: String?, locale: Locale = Locale.getDefault()): String? =
        parseResumeAt(hhmm)?.let { format12h(it, locale) }

    fun format12h(time: LocalTime, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("h:mm a", locale).format(time).lowerAsciiDayPeriod(locale)

    private fun String.lowerAsciiDayPeriod(locale: Locale): String =
        if (locale.language == "en") this.replace("AM", "am").replace("PM", "pm") else this
}
