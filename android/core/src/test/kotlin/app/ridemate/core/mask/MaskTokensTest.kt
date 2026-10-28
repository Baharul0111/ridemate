package app.ridemate.core.mask

import app.ridemate.core.fare.FrameBuilder
import app.ridemate.core.ocr.Box
import app.ridemate.core.ocr.OcrFrame
import app.ridemate.core.ocr.OcrToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskTokensTest {
    private fun OcrFrame.idOf(text: String) = tokens.indexOfFirst { it.text == text }

    private fun MaskResult.covers(box: Box) = rectsToBlur.any {
        it.l <= box.l && it.t <= box.t && it.r >= box.r && it.b >= box.b
    }

    @Test
    fun `status bar band is always blurred`() {
        val frame = FrameBuilder().line(40, 20, 30, "10:42", "Jio", "4G").line(150, 500, 40, "Auto").build()
        val result = maskTokens(frame)
        assertEquals(Box(0, 0, 1080, 108), result.rectsToBlur.first())
        assertTrue(PiiKind.STATUS_BAR in result.kinds)
        assertEquals(listOf("<m>", "<m>", "<m>", "Auto"), result.maskedTokens.map { it.text })
        val empty = maskTokens(OcrFrame(1080, 2400, emptyList()))
        assertEquals(listOf(Box(0, 0, 1080, 108)), empty.rectsToBlur)
    }

    @Test
    fun `assigned screen hides name, plate, OTP and phone but keeps prices`() {
        val frame = FrameBuilder()
            .line(100, 400, 40, "Captain", "Ramesh", "is", "arriving")
            .line(100, 470, 40, "AS", "01", "AB", "1234")
            .line(100, 540, 40, "OTP")
            .line(100, 600, 60, "4", "8", "2", "1")
            .line(100, 700, 40, "Call", "+91", "98765", "43210")
            .line(100, 780, 40, "Fare", "₹1,234")
            .build()
        val result = maskTokens(frame)
        val masked = result.maskedTokens.map { it.text }
        assertEquals(
            listOf(
                "Captain", "<m>", "is", "arriving", "<m>", "<m>", "<m>", "<m>", "OTP", "<m>", "<m>", "<m>", "<m>",
                "Call", "<m>", "<m>", "<m>", "Fare", "₹1,234",
            ),
            masked,
        )
        for (text in listOf("Ramesh", "AS", "1234", "4", "1", "+91", "43210")) {
            assertTrue(text, result.covers(frame.tokens[frame.idOf(text)].box))
        }
        assertFalse(result.covers(frame.tokens[frame.idOf("₹1,234")].box))
        assertFalse(frame.idOf("₹1,234") in result.maskedTokenIds)
        assertEquals(setOf(PlateHash.sha256("AS01AB1234")), result.plateHashes)
        assertEquals(setOf(PiiKind.NAME, PiiKind.PLATE, PiiKind.OTP, PiiKind.PHONE, PiiKind.STATUS_BAR), result.kinds)
        assertFalse(result.toString().contains("AS01AB1234"))
    }

    @Test
    fun `only the PII part of a long token is blurred`() {
        val token = OcrToken("Call 9876543210 for help", Box(100, 500, 1000, 540), lineId = 0)
        val result = maskTokens(OcrFrame(1080, 2400, listOf(token)))
        assertEquals("Call <m> for help", result.maskedTokens.single().text)
        val slice = result.rectsToBlur.last()
        // Glyphs are 900/24 = 37.5 px; digits are chars 5..14, so the slice spans ~[287, 662] plus half a glyph.
        assertTrue("$slice", slice.l in 250..290 && slice.r in 660..700)
        assertTrue(slice.t < 500 && slice.b > 540)
    }

    @Test
    fun `hindi assigned screen`() {
        val frame = FrameBuilder()
            .line(100, 400, 40, "कैप्टन", "रमेश", "आ", "रहे", "हैं")
            .line(100, 470, 40, "ओटीपी", "४८२१")
            .line(100, 540, 40, "AS", "०१", "AB", "१२३४")
            .build()
        val result = maskTokens(frame)
        assertEquals(listOf("कैप्टन", "<m>", "आ", "रहे", "हैं", "ओटीपी", "<m>", "<m>", "<m>", "<m>", "<m>"), result.maskedTokens.map { it.text })
        assertEquals(setOf(PlateHash.sha256("AS01AB1234")), result.plateHashes)
    }
}
