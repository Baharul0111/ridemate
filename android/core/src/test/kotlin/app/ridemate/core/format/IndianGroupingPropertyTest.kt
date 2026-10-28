package app.ridemate.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/** Seeded property tests: formatting and parsing are exact inverses for every price the app can show. */
class IndianGroupingPropertyTest {
    private val random = Random(20261028)

    private fun randomAmount(): Int = when (random.nextInt(4)) {
        0 -> random.nextInt(0, 1_000)
        1 -> random.nextInt(1_000, 100_000)
        2 -> random.nextInt(100_000, 10_000_000)
        else -> random.nextInt(10_000_000, 999_999_999)
    }

    @Test
    fun `Indian-grouped amounts round-trip in both digit scripts`() {
        repeat(3_000) {
            val n = randomAmount()
            val numerals = if (random.nextBoolean()) Numerals.LATIN else Numerals.DEVANAGARI
            val text = IndianFormat.rupees(n.toLong(), numerals)
            val parsed = RupeeParser.parse(text)
            requireNotNull(parsed) { "failed on $text" }
            assertEquals(text, n, parsed.min)
            assertEquals(text, n, parsed.max)
            assertEquals(n, RupeeParser.parseNumber(IndianFormat.group(n.toLong(), numerals)))
        }
    }

    @Test
    fun `ranges and western grouping round-trip`() {
        repeat(2_000) {
            val a = randomAmount()
            val b = a + random.nextInt(0, 5_000).coerceAtMost(999_999_999 - a)
            val range = IndianFormat.rupeeRange(a..b)
            val parsed = RupeeParser.parse(range)!!
            assertEquals(range, a..b, parsed.range)
            val western = "₹" + "%,d".format(java.util.Locale.US, a)
            assertEquals(western, a, RupeeParser.parse(western)!!.min)
        }
    }

    @Test
    fun `a digit lost from a lakh group never yields a number`() {
        repeat(2_000) {
            val n = random.nextInt(100_000, 999_999_999)
            val groups = IndianFormat.group(n.toLong()).split(',')
            // Middle groups have two digits; dropping one leaves a group neither Indian nor western grouping allows.
            val middle = random.nextInt(1, groups.size - 1)
            val corrupted = groups.toMutableList().also { it[middle] = it[middle].drop(1) }.joinToString(",")
            assertNull(corrupted, RupeeParser.parse("₹$corrupted"))
            // An OCR space in place of a comma is not guessed back either.
            val spaced = groups.joinToString(",").replaceFirst(',', ' ')
            assertNull(spaced, RupeeParser.parse("₹$spaced"))
        }
    }
}
