package app.ridemate.core.fare

import app.ridemate.core.format.Digits
import app.ridemate.core.format.IndianFormat
import app.ridemate.core.format.Numerals
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.ocr.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Seeded property test over generated fare screens: whatever the layout, the parser picks each row's payable price
 * and never a struck one (smaller, crossed by a marker, or written with strokes).
 */
class FareParserPropertyTest {
    private val random = Random(1_000_003)
    private val parser = TestConfigs.parser()
    private val labels = listOf(
        "Bike" to VehicleClass.BIKE, "Auto" to VehicleClass.AUTO, "Uber Go" to VehicleClass.CAB_ECONOMY,
        "Mini" to VehicleClass.CAB_ECONOMY, "Premier" to VehicleClass.CAB_PREMIUM, "XL" to VehicleClass.CAB_XL,
    )

    private enum class Strike { NONE, SMALLER, MARKER, STROKES }

    private data class Generated(val vehicleClass: VehicleClass, val payable: IntRange, val struck: Int?)

    private fun priceText(range: IntRange): String {
        val numerals = if (random.nextInt(5) == 0) Numerals.DEVANAGARI else Numerals.LATIN
        if (range.first != range.last) return IndianFormat.rupeeRange(range, numerals)
        return when (random.nextInt(4)) {
            0 -> "Rs. ${range.first}"
            else -> IndianFormat.rupees(range.first.toLong(), numerals)
        }
    }

    @Test
    fun `never returns a struck price and always finds the payable`() {
        var struckRows = 0
        repeat(1_200) { screen ->
            val builder = FrameBuilder()
            builder.line(40, 20, 30, "10:42")
            val generated = mutableListOf<Generated>()
            val rowCount = 1 + random.nextInt(6)
            val pitch = 170 + random.nextInt(60)
            for (i in 0 until rowCount) {
                val (label, vehicleClass) = labels.random(random)
                val y = 400 + i * pitch
                val h = 40 + random.nextInt(25)
                val base = 15 + random.nextInt(3_000)
                val payable = if (random.nextInt(5) == 0) base..base + 5 + random.nextInt(40) else base..base
                val right = 1000 - random.nextInt(20)
                builder.line(150, y, 40, *label.split(" ").toTypedArray())
                val payableText = priceText(payable)
                val strike = Strike.entries.random(random)
                // Struck prices are usually higher than the payable; keep them distinct so a wrong pick is visible.
                val struckValue = payable.last + 10 + random.nextInt(200)
                when (strike) {
                    Strike.NONE, Strike.SMALLER -> builder.right(payableText, right, y - (h - 40) / 2, h)
                    Strike.MARKER, Strike.STROKES -> {
                        // Same height and to the right of the payable: only the strike evidence can rule it out.
                        val struckText = IndianFormat.rupees(struckValue.toLong())
                        val shown = if (strike == Strike.STROKES) struckText.map { "$it̶" }.joinToString("") else struckText
                        builder.right(shown, right, y - (h - 40) / 2, h)
                        val struckWidth = FrameBuilder.glyphWidth(shown, h)
                        val payableRight = right - struckWidth - 20 - random.nextInt(40)
                        builder.right(payableText, payableRight, y - (h - 40) / 2, h)
                        if (strike == Strike.MARKER) {
                            val mid = y - (h - 40) / 2 + h / 2
                            builder.token("—", Box(right - struckWidth + 2, mid - 2, right - 2, mid + 2))
                        }
                    }
                }
                builder.line(150, y + h / 2 + 40, 28, "${1 + random.nextInt(20)}", "min", "away")
                if (strike == Strike.SMALLER) {
                    val sh = (h * (0.45 + random.nextDouble() * 0.33)).roundToInt()
                    val atRight = random.nextBoolean()
                    val sRight = if (atRight) right else right - FrameBuilder.glyphWidth(payableText, h) - 30
                    val sTop = if (atRight) y + h / 2 + 40 else y + (h - sh) / 2 - (h - 40) / 2
                    builder.right(IndianFormat.rupees(struckValue.toLong()), sRight, sTop, sh)
                }
                generated += Generated(vehicleClass, payable, if (strike == Strike.NONE) null else struckValue)
            }
            val frame = builder.build()
            val read = parser.parse(frame)
            val dump = read.rows.joinToString("\n") { r -> r.tokenIds.joinToString(" | ") { "${frame.tokens[it].text}@${frame.tokens[it].box}" } }
            assertEquals("screen $screen\n$dump", generated.size, read.rows.size)
            read.rows.zip(generated).forEach { (row, expected) ->
                val chosen = row.price?.amount?.range
                assertEquals("screen $screen row ${row.index}: ${row.tokenIds}", expected.payable, chosen)
                assertEquals(expected.vehicleClass, row.vehicleClass)
                if (expected.struck != null) {
                    struckRows++
                    assertNotEquals(expected.struck, chosen!!.first)
                    assertEquals(expected.struck, row.ignoredPrices.single().price.amount.min)
                }
            }
        }
        // Make sure the property actually exercised struck prices.
        assertTrue("only $struckRows struck rows", struckRows > 1_000)
    }

    @Test
    fun `digit scripts give the same reading`() {
        repeat(300) {
            val amount = 15 + random.nextInt(4_000)
            val latin = parser.parse(fareScreen(listOf(RowSpec("Auto", IndianFormat.rupees(amount.toLong())))))
            val hindi = parser.parse(fareScreen(listOf(RowSpec("Auto", Digits.write(IndianFormat.rupees(amount.toLong()), Numerals.DEVANAGARI)))))
            assertEquals(latin.rows.single().price!!.amount.range, hindi.rows.single().price!!.amount.range)
        }
    }
}
