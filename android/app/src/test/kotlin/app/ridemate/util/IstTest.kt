package app.ridemate.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import java.util.TimeZone

class IstTest {
    // 2026-10-28T08:00:05Z = 13:30:05 IST
    private val instant = 1_793_174_405_000L

    @Test
    fun `formats in IST regardless of the phone zone`() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("1:30 pm", Ist.time12h(instant, Locale.ENGLISH))
            assertEquals("1:30:05 pm", Ist.time12hWithSeconds(instant, Locale.ENGLISH))
            assertEquals("13:30", Ist.time24h(instant))
            assertEquals("28 Oct 2026, 1:30 pm", Ist.dateTime(instant, Locale.ENGLISH))
            assertEquals(LocalDate.of(2026, 10, 28), Ist.date(instant))
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun `IST day boundary is at 18 30 UTC`() {
        val justBeforeMidnightIst = 1_793_212_199_000L // 2026-10-28T18:29:59Z
        assertEquals(LocalDate.of(2026, 10, 28), Ist.date(justBeforeMidnightIst))
        assertEquals(LocalDate.of(2026, 10, 29), Ist.date(justBeforeMidnightIst + 1_000))
    }

    @Test
    fun `resume time from the server`() {
        assertEquals(LocalTime.of(13, 30), Ist.parseResumeAt("13:30"))
        assertEquals("1:30 pm", Ist.formatResumeAt("13:30", Locale.ENGLISH))
        assertEquals("12:05 am", Ist.formatResumeAt("00:05", Locale.ENGLISH))
        assertNull(Ist.parseResumeAt(null))
        assertNull(Ist.parseResumeAt("25:00"))
        assertNull(Ist.formatResumeAt("soon"))
    }

    @Test
    fun `hindi formats the same IST time in 12-hour form`() {
        val text = Ist.time12h(instant, Locale.forLanguageTag("hi-IN"))
        assertEquals(true, text.startsWith("1:30"))
        assertEquals(Ist.time12h(instant + 12 * 3_600_000L, Locale.forLanguageTag("hi-IN")).startsWith("1:30"), true)
    }

    @Test
    fun `dispatcher providers`() {
        val single = SingleDispatcherProvider(kotlinx.coroutines.Dispatchers.Unconfined)
        assertEquals(single.io, single.main)
        assertEquals(kotlinx.coroutines.Dispatchers.IO, DefaultDispatcherProvider.io)
    }
}
