package app.ridemate.core.mask

import app.ridemate.core.format.Digits
import app.ridemate.core.format.IndianFormat
import app.ridemate.core.format.Numerals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Seeded property tests: PII never survives masking, prices always do. */
class MaskPropertyTest {
    private val random = Random(4242)
    private val fillers = listOf(
        "Your ride is confirmed", "Fare", "pickup in", "min away", "Book Auto", "Choose a ride", "Uber Go", "Share trip",
        "आपकी राइड", "मिनट", "सुरक्षा", "Moto", "Premier", "trip details", "Rate your ride",
    )
    private val states = listOf("AS", "DL", "KA", "MH", "TN", "WB", "UP", "KL", "GJ", "RJ")

    private fun digits(n: Int) = (1..n).joinToString("") { random.nextInt(10).toString() }

    private fun phone(): Pair<String, String> {
        val number = (6 + random.nextInt(4)).toString() + digits(9)
        val prefix = listOf("", "+91 ", "+91-", "0", "91", "0091 ", "+91").random(random)
        val body = when (random.nextInt(4)) {
            0 -> number
            1 -> number.substring(0, 5) + " " + number.substring(5)
            2 -> number.substring(0, 5) + "-" + number.substring(5)
            else -> number.chunked(2).joinToString(" ")
        }
        return number to prefix + body
    }

    private fun plate(): Pair<String, String> {
        val state = states.random(random)
        val rto = (1 + random.nextInt(99)).toString().padStart(2, '0')
        val series = (1..(1 + random.nextInt(2))).map { 'A' + random.nextInt(26) }.joinToString("")
        val number = digits(4)
        val sep = listOf("", " ", "-").random(random)
        return "$state$rto$series$number" to listOf(state, rto, series, number).joinToString(sep)
    }

    private fun maybeDevanagari(text: String) = if (random.nextInt(4) == 0) Digits.write(text, Numerals.DEVANAGARI) else text

    private fun sentence(vararg parts: String) = (parts.toList() + List(random.nextInt(3)) { fillers.random(random) })
        .shuffled(random).joinToString(" · ")

    @Test
    fun `phone numbers never survive`() {
        repeat(1_500) {
            val (number, written) = phone()
            val text = sentence(maybeDevanagari(written))
            val masked = maskText(text)
            val leftDigits = Digits.toAscii(masked).filter { it.isDigit() }
            assertFalse("$text → $masked", leftDigits.contains(number.takeLast(6)))
        }
    }

    @Test
    fun `plates never survive and always hash to the canonical plate`() {
        repeat(1_500) {
            val (canonical, written) = plate()
            val text = sentence(maybeDevanagari(written))
            val masked = maskText(text)
            assertFalse("$text → $masked", masked.contains(written) || Digits.toAscii(masked).contains(canonical.takeLast(4)))
            assertEquals(text, PlateHash.sha256(canonical), PlateHash.of(text))
        }
    }

    @Test
    fun `OTPs next to their label never survive`() {
        val labels = listOf("OTP", "PIN", "Start ride code", "ओटीपी", "पिन")
        repeat(1_500) {
            val code = digits(4 + random.nextInt(3))
            val written = if (random.nextBoolean()) code else code.toList().joinToString(" ")
            val label = labels.random(random)
            val text = if (random.nextBoolean()) "$label: ${maybeDevanagari(written)}" else "${maybeDevanagari(written)} is your $label"
            val masked = maskText(sentence(text))
            assertFalse("$text → $masked", Digits.toAscii(masked).filter { it.isDigit() }.contains(code))
        }
    }

    @Test
    fun `driver names next to an anchor never survive`() {
        val given = listOf("Ramesh", "Priya", "Mohd.", "Sunil", "Anjali", "Imran", "Kavya", "Arjun", "रमेश", "सुनील", "प्रिया")
        val family = listOf("Kumar", "Das", "Sharma", "Irfan", "Baruah", "K.", "कुमार", "दास")
        val before = listOf("Captain", "Your driver", "Driver:", "Ride with", "कैप्टन", "ड्राइवर")
        val after = listOf("is on the way", "is arriving", "has arrived", "is your captain", "आ रहे हैं", "पहुंच गए")
        repeat(1_500) {
            val name = (listOf(given.random(random)) + List(random.nextInt(3)) { family.random(random) }).joinToString(" ")
            val text = if (random.nextBoolean()) "${before.random(random)} $name" else "$name ${after.random(random)}"
            val masked = maskText(sentence(text))
            name.split(" ").forEach { part ->
                assertFalse("$text → $masked", masked.split(Regex("[\\s·:]+")).contains(part))
            }
        }
    }

    @Test
    fun `prices are never masked`() {
        repeat(1_500) {
            val amount = 15 + random.nextInt(5_000)
            val price = when (random.nextInt(4)) {
                0 -> IndianFormat.rupees(amount.toLong())
                1 -> "Rs. $amount"
                2 -> IndianFormat.rupeeRange(amount..amount + random.nextInt(1, 40))
                else -> IndianFormat.rupees(amount.toLong(), Numerals.DEVANAGARI)
            }
            val text = sentence(price, "${1 + random.nextInt(30)} min")
            assertTrue("$text → ${maskText(text)}", maskText(text).contains(price))
        }
    }
}
