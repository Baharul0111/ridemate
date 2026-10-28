package app.ridemate.core.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NormalisationTest {

    @Test
    fun `lower-cases, collapses whitespace and replaces digits with hash`() {
        assertEquals("captain is # min away · #.# km", Normaliser.normalise("  Captain   is 4 min\naway ·\t1.2 KM "))
    }

    @Test
    fun `devanagari digits are digits too`() {
        assertEquals("कप्तान # मिनट में", Normaliser.normalise("कप्तान ४ मिनट में"))
    }

    @Test
    fun `masked spans become a single mask token before any other rewriting`() {
        val text = "OTP 4821 for AS01AB1234 call 9876543210"
        val otp = text.indexOf("4821").let { it until it + 4 }
        val plate = text.indexOf("AS01AB1234").let { it until it + 10 }
        val phone = text.indexOf("9876543210").let { it until it + 10 }
        assertEquals("otp <m> for <m> call <m>", Normaliser.normalise(text, listOf(phone, otp, plate)))
        assertEquals("OTP <m> for <m> call <m>", Normaliser.maskForUpload(text, listOf(otp, plate, phone)))
    }

    @Test
    fun `overlapping and adjacent spans merge, out-of-range spans are clipped`() {
        assertEquals("a <m> d", Normaliser.normalise("a bbcc d", listOf(2..3, 4..5, 3..4)))
        assertEquals("x <m>", Normaliser.normalise("x yz", listOf(2..40)))
    }

    @Test
    fun `a countdown normalises to one wording so it can only prompt once`() {
        assertEquals(
            Normaliser.normalise("Looking for a captain · 00:12"),
            Normaliser.normalise("Looking for a captain · 00:58"),
        )
    }

    @Test
    fun `structural fingerprint buckets post time by 10 s and keys on id and tag`() {
        fun post(id: Int, time: Long, tag: String? = null) =
            PostSnapshot("com.rapido.passenger", "k", id, tag, "ride_status", "status", true, null, null, time, 1)
        val a = StructuralFingerprint.of(post(1, 100_000))
        assertEquals(a, StructuralFingerprint.of(post(1, 109_999)))
        assertNotEquals(a, StructuralFingerprint.of(post(1, 110_000)))
        assertNotEquals(a, StructuralFingerprint.of(post(2, 100_000)))
        assertNotEquals(a, StructuralFingerprint.of(post(1, 100_000, tag = "pin")))
    }

    @Test
    fun `plates hash the same however they are spaced or cased, and short reads give no hash`() {
        val hash = PlateHasher.hash("AS 01 AB 1234")!!
        assertEquals(64, hash.length)
        assertEquals(hash, PlateHasher.hash("as01ab1234"))
        assertEquals(hash, PlateHasher.hash("AS-01-AB-१२३४"))
        assertNotEquals(hash, PlateHasher.hash("AS01AB1235"))
        assertNull(PlateHasher.hash("A1"))
    }

    @Test
    fun `empty or platform-redacted posts are redacted`() {
        fun post(title: String?, text: String?, flag: Boolean = false) =
            PostSnapshot("p", "k", 1, null, null, null, false, title, text, 0, 1, flag)
        assertEquals(true, post(null, null).isRedacted)
        assertEquals(true, post(" ", "").isRedacted)
        assertEquals(true, post("Rapido", "Sensitive content hidden", flag = true).isRedacted)
        assertEquals(false, post("Rapido", "Captain on the way").isRedacted)
        assertEquals("Rapido\nCaptain", post("Rapido", "Captain").matchText)
    }
}
