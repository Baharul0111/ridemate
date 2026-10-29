package app.ridemate.core.format

import app.ridemate.core.format.AmountFlag.CURRENCY_FROM_LEADING_TWO
import app.ridemate.core.format.AmountFlag.INDIAN_GROUPING
import app.ridemate.core.format.AmountFlag.NATIVE_DIGITS
import app.ridemate.core.format.AmountFlag.NO_CURRENCY_MARKER
import app.ridemate.core.format.AmountFlag.OCR_CURRENCY_CONFUSION
import app.ridemate.core.format.AmountFlag.RANGE
import app.ridemate.core.format.AmountFlag.ROUNDED_DECIMAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RupeeParserTest {
    private data class Case(val input: String, val min: Int, val max: Int, val flags: Set<AmountFlag> = emptySet())

    @Test
    fun `parses every Indian price format`() {
        val cases = listOf(
            Case("₹1,234", 1234, 1234),
            Case("₹1234", 1234, 1234),
            Case("Rs 1234", 1234, 1234),
            Case("Rs. 1234", 1234, 1234),
            Case("Rs.1234", 1234, 1234),
            Case("RS 99", 99, 99),
            Case("rs 99", 99, 99),
            Case("INR 1,234", 1234, 1234),
            Case("INR1234", 1234, 1234),
            Case("₹ 1,23,456", 123456, 123456, setOf(INDIAN_GROUPING)),
            Case("₹1,23,45,678", 12345678, 12345678, setOf(INDIAN_GROUPING)),
            Case("₹123,456", 123456, 123456),
            Case("₹110–130", 110, 130, setOf(RANGE)),
            Case("₹110-130", 110, 130, setOf(RANGE)),
            Case("₹110 - ₹130", 110, 130, setOf(RANGE)),
            Case("₹110 — ₹130", 110, 130, setOf(RANGE)),
            Case("Rs 110 to Rs 130", 110, 130, setOf(RANGE)),
            Case("₹120–120", 120, 120),
            Case("₹१२०", 120, 120, setOf(NATIVE_DIGITS)),
            Case("₹१,२३,४५६", 123456, 123456, setOf(NATIVE_DIGITS, INDIAN_GROUPING)),
            Case("₹१२०–१४०", 120, 140, setOf(NATIVE_DIGITS, RANGE)),
            Case("रु १२०", 120, 120, setOf(NATIVE_DIGITS)),
            Case("रु. 85", 85, 85),
            Case("₹৮৫", 85, 85, setOf(NATIVE_DIGITS)),
            Case("₹99.50", 100, 100, setOf(ROUNDED_DECIMAL)),
            Case("₹99.49", 99, 99, setOf(ROUNDED_DECIMAL)),
            Case("₹99.5", 100, 100, setOf(ROUNDED_DECIMAL)),
            Case("₹212.43", 212, 212, setOf(ROUNDED_DECIMAL)),
            Case("₹1,234.50", 1235, 1235, setOf(ROUNDED_DECIMAL)),
            Case("₹99.00", 99, 99),
            Case("₹110/-", 110, 110),
            Case("Rs. 1,234/-", 1234, 1234),
            Case("Z110", 110, 110, setOf(OCR_CURRENCY_CONFUSION)),
            Case("z110", 110, 110, setOf(OCR_CURRENCY_CONFUSION)),
            Case("R110", 110, 110, setOf(OCR_CURRENCY_CONFUSION)),
            Case("%110", 110, 110, setOf(OCR_CURRENCY_CONFUSION)),
            Case("Z1,234", 1234, 1234, setOf(OCR_CURRENCY_CONFUSION)),
            Case("  ₹ 450  ", 450, 450),
            Case("₹0", 0, 0),
        )
        for (c in cases) {
            val parsed = RupeeParser.parse(c.input)
            requireNotNull(parsed) { "expected ${c.input} to parse" }
            assertEquals(c.input, c.min, parsed.min)
            assertEquals(c.input, c.max, parsed.max)
            assertEquals(c.input, c.flags, parsed.flags)
            assertEquals(c.input.trim(), parsed.raw)
        }
    }

    @Test
    fun `refuses to guess`() {
        val rejected = listOf(
            "", "₹", "Rs", "1234", "2110", "₹1,2345", "₹1,23", "₹12,3456", "₹1,234,", "₹ 1 234", "₹130–110",
            "₹110 ⓘ", "₹1̶5̶0̶", "₹0110", "₹1234567890", "₹99.505", "₹99.", "Rs 1,23,4567", "₹-110", "about ₹110",
            "Z 110", "₹11O", "₹110–", "₹ 110 – ",
        )
        for (input in rejected) assertNull(input, RupeeParser.parse(input))
    }

    @Test
    fun `a leading 2 becomes the rupee sign only with layout context`() {
        assertNull(RupeeParser.parse("2110"))
        assertEquals(2110, RupeeParser.parse("2110", requireCurrency = false)!!.min)
        val withContext = RupeeParser.parse("2110", CurrencyContext.LEADING_TWO_IS_RUPEE)!!
        assertEquals(110, withContext.min)
        assertEquals(setOf(CURRENCY_FROM_LEADING_TWO), withContext.flags)
        assertEquals(1234, RupeeParser.parse("21,234", CurrencyContext.LEADING_TWO_IS_RUPEE)!!.min)
        // The context never rewrites a real ₹ or a number that doesn't start with 2.
        assertEquals(2110, RupeeParser.parse("₹2110", CurrencyContext.LEADING_TWO_IS_RUPEE)!!.min)
        assertNull(RupeeParser.parse("3110", CurrencyContext.LEADING_TWO_IS_RUPEE))
        assertNull(RupeeParser.parse("2", CurrencyContext.LEADING_TWO_IS_RUPEE))
    }

    @Test
    fun `the price column reads ML Kit's rupee-glyph misreads and bare amounts, never text alone`() {
        // Recorded on the phone (eval/recorded/ocr): ₹264 → {264, ₹272 → T272, ₹289 → 7289, ₹176 → 176.
        val col = CurrencyContext.PRICE_COLUMN
        assertEquals(264, RupeeParser.parse("{264", col)!!.min)
        assertEquals(setOf(OCR_CURRENCY_CONFUSION), RupeeParser.parse("T272", col)!!.flags)
        assertEquals(272, RupeeParser.parse("T272", col)!!.min)
        assertEquals(289, RupeeParser.parse("7289", col)!!.min)
        assertEquals(setOf(CURRENCY_FROM_LEADING_TWO), RupeeParser.parse("7289", col)!!.flags)
        assertEquals(110, RupeeParser.parse("2110", col)!!.min)
        assertEquals(setOf(NO_CURRENCY_MARKER), RupeeParser.parse("176", col)!!.flags)
        assertEquals(180..210, RupeeParser.parse("180-210", col)!!.range)
        assertEquals(1040, RupeeParser.parse("1,040", col)!!.min)
        // A leading 2/7 is the ₹ only on a comma-less amount of 4+ digits: 789 and 7,289 stay as written.
        assertEquals(789, RupeeParser.parse("789", col)!!.min)
        assertEquals(7289, RupeeParser.parse("7,289", col)!!.min)
        // Without the layout context none of these is an amount.
        assertNull(RupeeParser.parse("{264"))
        assertNull(RupeeParser.parse("T272"))
        assertNull(RupeeParser.parse("176"))
        assertEquals(7289, RupeeParser.parse("7289", requireCurrency = false)!!.min)
    }

    @Test
    fun `bare numbers parse only on request`() {
        assertEquals(123456, RupeeParser.parseNumber("1,23,456"))
        assertEquals(120, RupeeParser.parseNumber("१२०"))
        assertEquals(1234, RupeeParser.parseNumber("1,234"))
        assertNull(RupeeParser.parseNumber("110–130"))
        assertNull(RupeeParser.parseNumber("1,2,3"))
        assertTrue(NO_CURRENCY_MARKER in RupeeParser.parse("450", requireCurrency = false)!!.flags)
    }

    @Test
    fun `currency confusion helpers keep offsets`() {
        assertTrue(RupeeParser.hasCurrencyConfusion("Z110"))
        assertTrue(RupeeParser.hasCurrencyConfusion("%१२०"))
        assertFalse(RupeeParser.hasCurrencyConfusion("Zip"))
        assertFalse(RupeeParser.hasCurrencyConfusion("Rs110"))
        assertEquals(" ₹110", RupeeParser.fixCurrencyConfusion(" Z110"))
        assertEquals("Zone 1", RupeeParser.fixCurrencyConfusion("Zone 1"))
    }
}
