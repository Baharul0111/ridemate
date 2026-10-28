package app.ridemate.core.mask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MaskTextTest {
    private fun assertMasked(input: String, expected: String) = assertEquals(input, expected, maskText(input))

    @Test
    fun `masks Indian phone numbers in every common layout`() {
        assertMasked("Call 9876543210 now", "Call <m> now")
        assertMasked("Call +91 98765 43210", "Call <m>")
        assertMasked("Call +91-9876543210.", "Call <m>.")
        assertMasked("Ph: 098765-43210", "Ph: <m>")
        assertMasked("0091 98765 43210", "<m>")
        assertMasked("91 9876543210", "<m>")
        assertMasked("98 76 54 32 10", "<m>")
        assertMasked("९८७६५४३२१०", "<m>")
        assertMasked("Office 0361-2345678", "Office <m>")
        // Not phones: prices, short numbers, 11-digit runs, numbers starting below 6.
        assertMasked("₹9876543210", "₹9876543210")
        assertMasked("Trip 5876543210", "Trip 5876543210")
        assertMasked("98765432101", "98765432101")
        assertMasked("Fare ₹1,234 · 3 min", "Fare ₹1,234 · 3 min")
    }

    @Test
    fun `masks OTP and PIN codes near their labels`() {
        assertMasked("OTP 4821", "OTP <m>")
        assertMasked("Your OTP is 4821. Share with captain", "Your OTP is <m>. Share with captain")
        assertMasked("PIN: 4 8 2 1", "PIN: <m>")
        assertMasked("Start ride code 482915", "Start ride code <m>")
        assertMasked("4821 is your OTP", "<m> is your OTP")
        assertMasked("ओटीपी: ४८२१", "ओटीपी: <m>")
        assertMasked("पिन 4821 कैप्टन को बताएं", "पिन <m> कैप्टन को बताएं")
        // Prices and ETAs near the word are left alone; an unlabelled 4-digit number is too.
        assertMasked("OTP ₹1234", "OTP ₹1234")
        assertMasked("OTP valid for 10 min", "OTP valid for 10 min")
        assertMasked("Ride 4821 confirmed", "Ride 4821 confirmed")
    }

    @Test
    fun `masks number plates in every layout`() {
        assertMasked("AS 01 AB 1234", "<m>")
        assertMasked("AS01AB1234 arriving", "<m> arriving")
        assertMasked("AS-01-AB-1234", "<m>")
        assertMasked("DL 3C AB 1234", "<m>")
        assertMasked("KA05MJ0042", "<m>")
        assertMasked("Vehicle 22 BH 1234 AA", "Vehicle <m>")
        assertMasked("22BH1234AA", "<m>")
        assertMasked("AS ०१ AB १२३४", "<m>")
        assertMasked("MH 12 1234", "<m>")
        // Not plates.
        assertMasked("UP TO 50% OFF", "UP TO 50% OFF")
        assertMasked("as 10 mins", "as 10 mins")
        assertMasked("AS 12", "AS 12")
    }

    @Test
    fun `masks driver names after anchor words only`() {
        assertMasked("Captain Ramesh is on the way", "Captain <m> is on the way")
        assertMasked("Your driver, Ramesh K., is arriving", "Your driver, <m>, is arriving")
        assertMasked("Driver Ramesh Kumar Das 4.8★", "Driver <m> 4.8★")
        assertMasked("You are riding with Priya", "You are riding with <m>")
        assertMasked("कैप्टन रमेश आ रहे हैं", "कैप्टन <m> आ रहे हैं")
        assertMasked("ड्राइवर रमेश कुमार पहुंच गए", "ड्राइवर <m> पहुंच गए")
        assertMasked("Captain\nRamesh", "Captain\n<m>")
        // Anchor followed by status, brand or lowercase words: nothing to hide.
        assertMasked("Captain is arriving", "Captain is arriving")
        assertMasked("Your Rapido captain cancelled", "Your Rapido captain cancelled")
        assertMasked("Ride with Uber Go", "Ride with Uber Go")
        assertMasked("Pay with Cash", "Pay with Cash")
        assertMasked("Driver details", "Driver details")
    }

    @Test
    fun `masks driver names before trailing phrases`() {
        assertMasked("Ramesh is on the way", "<m> is on the way")
        assertMasked("Ramesh Kumar is arriving in 4 min", "<m> is arriving in 4 min")
        assertMasked("Mohd. Irfan K. has arrived", "<m> has arrived")
        assertMasked("Your ride: Priya is your driver", "Your ride: <m> is your driver")
        assertMasked("Driver Ramesh is on the way", "Driver <m> is on the way")
        assertMasked("रमेश आ रहे हैं", "<m> आ रहे हैं")
        assertMasked("कैप्टन रमेश पहुंच गए", "कैप्टन <m> पहुंच गए")
        // Brands, vehicles, roles and lowercase words before the phrase are not names.
        assertMasked("Your Uber is arriving", "Your Uber is arriving")
        assertMasked("Your ride is on the way", "Your ride is on the way")
        assertMasked("Rapido Captain is on the way", "Rapido Captain is on the way")
        assertMasked("Your Auto has arrived", "Your Auto has arrived")
        assertMasked("आपके कैप्टन आ रहे हैं", "आपके कैप्टन आ रहे हैं")
        assertMasked("is on the way", "is on the way")
    }

    @Test
    fun `empty anchor lists find nothing instead of everything`() {
        val cfg = MaskConfig(nameAnchors = emptyList(), trailingNameAnchors = emptyList(), otpAnchors = listOf(" "))
        assertEquals("Captain Ramesh · 4821 · Auto ₹110", maskText("Captain Ramesh · 4821 · Auto ₹110", cfg))
        assertEquals("Call <m>", maskText("Call 9876543210", cfg))
    }

    @Test
    fun `masks emails and combinations`() {
        assertMasked("Write to help.me@example.co.in", "Write to <m>")
        assertMasked(
            "Captain Ramesh (AS 01 AB 1234) is 4 min away. OTP 4821. Call +91 98765 43210",
            "Captain <m> (<m>) is 4 min away. OTP <m>. Call <m>",
        )
    }

    @Test
    fun `plate hashes ignore formatting and never keep the plate`() {
        val variants = listOf("AS 01 AB 1234", "AS01AB1234", "AS-01-AB-1234", "AS 1 AB 1234", "AS ०१ AB १२३४", "as seen: AS01 AB1234")
        val hashes = variants.map { PlateHash.of(it) }
        hashes.forEach { assertNotNull(it) }
        assertEquals(1, hashes.toSet().size)
        assertEquals(PlateHash.sha256("AS01AB1234"), hashes.first())
        assertEquals(64, hashes.first()!!.length)
        assertEquals(PlateHash.of("22 BH 1234 AA"), PlateHash.of("22BH1234AA"))
        assertNull(PlateHash.of("no plate here"))
    }
}
