package app.ridemate.core.format

import app.ridemate.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.math.abs

class IndianFormatTest {
    @Test
    fun `groups digits the Indian way`() {
        val cases = mapOf(
            0L to "0", 7L to "7", 999L to "999", 1000L to "1,000", 12345L to "12,345", 123456L to "1,23,456",
            1234567L to "12,34,567", 12345678L to "1,23,45,678", 100000L to "1,00,000", -123456L to "-1,23,456",
        )
        cases.forEach { (n, text) -> assertEquals(text, IndianFormat.group(n)) }
        assertEquals("₹1,23,456", IndianFormat.rupees(123456))
        assertEquals("₹१,२३,४५६", IndianFormat.rupees(123456, Numerals.DEVANAGARI))
    }

    @Test
    fun `formats price ranges`() {
        assertEquals("₹110–130", IndianFormat.rupeeRange(110..130))
        assertEquals("₹120", IndianFormat.rupeeRange(120..120))
        assertEquals("₹1,200–1,450", IndianFormat.rupeeRange(1200..1450))
        assertEquals("₹११०–१३०", IndianFormat.rupeeRange(110..130, Numerals.DEVANAGARI))
    }

    @Test
    fun `IST times ignore the device zone`() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("America/New_York", "UTC", "Asia/Tokyo")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                // 2026-10-28 07:35:09 UTC = 13:05:09 IST
                val ms = ZonedDateTime.parse("2026-10-28T07:35:09Z").toInstant().toEpochMilli()
                assertEquals("1:05:09 PM", IstTime.seenAt(ms))
                assertEquals("1:05 PM", IstTime.resumeTime(ms))
                assertEquals("१:०५:०९ PM", IstTime.seenAt(ms, Numerals.DEVANAGARI))
            }
        } finally {
            TimeZone.setDefault(original)
        }
        val midnight = ZonedDateTime.parse("2026-10-27T18:30:00Z").toInstant().toEpochMilli()
        assertEquals("12:00:00 AM", IstTime.seenAt(midnight))
    }

    @Test
    fun `server resume time is shown in 12-hour form`() {
        assertEquals("1:30 PM", IstTime.resumeAtIst("13:30"))
        assertEquals("9:05 AM", IstTime.resumeAtIst("09:05"))
        assertEquals("12:00 AM", IstTime.resumeAtIst("0:00"))
        assertEquals("१:३० PM", IstTime.resumeAtIst("13:30", Numerals.DEVANAGARI))
        assertNull(IstTime.resumeAtIst("25:00"))
        assertNull(IstTime.resumeAtIst(null))
    }

    @Test
    fun `digit scripts convert one char to one char`() {
        assertEquals("120 और 45", Digits.toAscii("१२० और ४५"))
        assertEquals("85", Digits.toAscii("৮৫"))
        assertEquals("१२०", Digits.write("120", Numerals.DEVANAGARI))
        val mixed = "Rs. ₹१,२३४ (৫)"
        assertEquals(mixed.length, Digits.toAscii(mixed).length)
    }

    @Test
    fun `haversine and distance text`() {
        // Guwahati railway station → LGBI airport is about 20 km in a straight line.
        val km = Geo.haversineKm(LatLng(26.1817, 91.7510), LatLng(26.1061, 91.5859))
        assertTrue("got $km", abs(km - 18.5) < 1.0)
        assertEquals(0.0, Geo.haversineKm(LatLng(26.0, 91.0), LatLng(26.0, 91.0)), 1e-9)

        assertEquals(1200, DistanceText.parseMeters("1.2 km"))
        assertEquals(1200, DistanceText.parseMeters("1.2km"))
        assertEquals(800, DistanceText.parseMeters("800 m"))
        assertEquals(1500, DistanceText.parseMeters("१.५ किमी"))
        assertEquals(3000, DistanceText.parseMeters("3 KM"))
        assertNull(DistanceText.parseMeters("near you"))
        assertNull(DistanceText.parseMeters("1.2 km away"))
        assertNull(DistanceText.parseMeters(null))

        assertEquals("1.2 km", DistanceText.format(1200))
        assertEquals("3 km", DistanceText.format(3000))
        assertEquals("800 m", DistanceText.format(800))
        assertEquals("१.२ km", DistanceText.format(1200, Numerals.DEVANAGARI))
    }
}
