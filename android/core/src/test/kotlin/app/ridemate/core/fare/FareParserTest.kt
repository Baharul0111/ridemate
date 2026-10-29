package app.ridemate.core.fare

import app.ridemate.core.config.FareParsersConfig
import app.ridemate.core.format.AmountFlag
import app.ridemate.core.model.Provider
import app.ridemate.core.model.ScreenType
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.ocr.Box
import app.ridemate.core.ocr.OcrFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FareParserTest {
    private val parser = TestConfigs.parser()

    private fun OcrFrame.text(id: Int) = tokens[id].text

    @Test
    fun `reads each row's label, payable price and ETA`() {
        val read = parser.parse(
            fareScreen(
                listOf(
                    RowSpec("Bike", "₹45", eta = "2 min away"),
                    RowSpec("Auto", "₹110", struck = "₹140"),
                    RowSpec("Cab Economy", "₹210", eta = "5 min away"),
                ),
            ),
        )
        assertEquals(3, read.rows.size)
        assertEquals(listOf(VehicleClass.BIKE, VehicleClass.AUTO, VehicleClass.CAB_ECONOMY), read.rows.map { it.vehicleClass })
        assertEquals(listOf("Bike", "Auto", "Cab Economy"), read.rows.map { it.label!!.text })
        assertEquals(listOf(45..45, 110..110, 210..210), read.rows.map { it.price!!.amount.range })
        assertEquals(listOf(2, 3, 5), read.rows.map { it.eta!!.value })
        assertEquals(listOf(0, 1, 2), read.rows.map { it.index })
        val struck = read.rows[1].ignoredPrices.single()
        assertEquals(140, struck.price.amount.min)
        assertEquals(IgnoredReason.SHORTER_THAN_PAYABLE, struck.reason)
        assertEquals(ScreenType.FARE_OPTIONS, read.screenType)
        assertTrue(read.looksLikeFareScreen)
        // The payable token's box is what the gate checks against Channel A's box.
        assertTrue(read.rows[1].price!!.box.r == 1000 && read.rows[1].price!!.tokenIds.size == 1)
    }

    @Test
    fun `tightly packed rows split at each new label`() {
        val read = parser.parse(
            fareScreen(listOf(RowSpec("Bike", "₹45"), RowSpec("Auto", "₹110"), RowSpec("Premier", "₹320")), pitch = 100),
        )
        assertEquals(listOf(45, 110, 320), read.rows.map { it.price!!.amount.min })
        assertEquals(listOf(VehicleClass.BIKE, VehicleClass.AUTO, VehicleClass.CAB_PREMIUM), read.rows.map { it.vehicleClass })
    }

    @Test
    fun `an option without a price does not absorb the next one`() {
        val frame = FrameBuilder()
            .line(150, 420, 40, "Auto").line(150, 470, 30, "Not", "available")
            .line(150, 520, 40, "Bike").right("₹45", 1000, 516, 48)
            .build()
        val rows = parser.parse(frame).rows
        assertEquals(listOf(VehicleClass.AUTO, VehicleClass.BIKE), rows.map { it.vehicleClass })
        assertNull(rows[0].price)
        assertEquals(45, rows[1].price!!.amount.min)
    }

    @Test
    fun `equal-height struck prices are ignored when OCR shows the strike`() {
        // Struck ₹150 sits right of the payable at the same height, crossed by a dash token.
        val marker = FrameBuilder().line(150, 600, 40, "Auto")
            .right("₹150", 1000, 596, 48).right("₹110", 860, 596, 48)
            .token("—", Box(892, 618, 998, 622))
            .build()
        val markerRow = parser.parse(marker).rows.single()
        assertEquals(110, markerRow.price!!.amount.min)
        assertEquals(IgnoredReason.STRIKE_MARKER, markerRow.ignoredPrices.single().reason)

        val strokes = FrameBuilder().line(150, 600, 40, "Auto")
            .right("₹1̶5̶0̶", 1000, 596, 48).right("₹110", 840, 596, 48).build()
        val strokesRow = parser.parse(strokes).rows.single()
        assertEquals(110, strokesRow.price!!.amount.min)
        assertEquals(150, strokesRow.ignoredPrices.single().price.amount.min)

        val tildes = FrameBuilder().line(150, 600, 40, "Auto")
            .right("~₹150~", 1000, 596, 48).right("₹110", 840, 596, 48).build()
        assertEquals(110, parser.parse(tildes).rows.single().price!!.amount.min)

        // Without any strike evidence the rightmost of the tallest wins; the gate must then disagree with A.
        val unmarked = FrameBuilder().line(150, 600, 40, "Auto")
            .right("₹150", 1000, 596, 48).right("₹110", 860, 596, 48).build()
        val row = parser.parse(unmarked).rows.single()
        assertEquals(150, row.price!!.amount.min)
        assertEquals(IgnoredReason.NOT_SELECTED, row.ignoredPrices.single().reason)
    }

    @Test
    fun `the tallest price wins over a taller-than-strike-ratio but shorter one`() {
        val frame = FrameBuilder().line(150, 600, 40, "Auto")
            .right("₹110", 700, 590, 60).right("₹99", 1000, 600, 50).build()
        val row = parser.parse(frame).rows.single()
        assertEquals(110, row.price!!.amount.min)
        assertEquals(IgnoredReason.NOT_SELECTED, row.ignoredPrices.single().reason)
    }

    @Test
    fun `merges prices split across tokens`() {
        val frame = FrameBuilder()
            .line(150, 400, 40, "Auto").line(800, 396, 48, "₹110", "–", "130")
            .line(150, 600, 40, "Bike").line(800, 596, 48, "₹", "45")
            .line(150, 800, 40, "Mini").line(800, 796, 48, "Rs.", "185")
            .line(150, 1000, 40, "XL").line(780, 996, 48, "₹410–", "₹460")
            .line(150, 1200, 40, "Premier").line(760, 1196, 48, "₹520", "-₹560")
            .build()
        val rows = parser.parse(frame).rows
        assertEquals(listOf(110..130, 45..45, 185..185, 410..460, 520..560), rows.map { it.price!!.amount.range })
        assertEquals(3, rows[0].price!!.tokenIds.size)
        assertTrue(AmountFlag.RANGE in rows[0].price!!.amount.flags)
    }

    @Test
    fun `normalises OCR currency confusions`() {
        val frame = FrameBuilder()
            .line(150, 400, 40, "Auto").right("Z110", 1000, 396, 48)
            .line(150, 600, 40, "Bike").right("R45", 1000, 596, 48)
            .line(150, 800, 40, "Mini").right("%185", 1000, 796, 48)
            .build()
        val rows = parser.parse(frame).rows
        assertEquals(listOf(110, 45, 185), rows.map { it.price!!.amount.min })
        rows.forEach { assertTrue(AmountFlag.OCR_CURRENCY_CONFUSION in it.price!!.amount.flags) }
    }

    @Test
    fun `a leading 2 is a rupee sign only in the price column at price height`() {
        val inColumn = parser.parse(
            fareScreen(listOf(RowSpec("Bike", "₹45"), RowSpec("Auto", "2110"), RowSpec("Mini", "₹185"))),
        )
        val auto = inColumn.rows[1]
        assertEquals(110, auto.price!!.amount.min)
        assertTrue(AmountFlag.CURRENCY_FROM_LEADING_TWO in auto.price!!.amount.flags)

        // Alone on screen there is no column to compare with: 2110 is not turned into anything.
        val alone = parser.parse(fareScreen(listOf(RowSpec("Auto", "2110"))))
        assertNull(alone.rows.singleOrNull()?.price)

        // Out of the price column (e.g. a seat count or a code) it stays a plain number.
        val offColumn = FrameBuilder()
            .line(150, 400, 40, "Bike").right("₹45", 1000, 396, 48)
            .line(150, 600, 40, "Auto", "2110").right("₹1", 1000, 610, 20)
            .line(150, 800, 40, "Mini").right("₹185", 1000, 796, 48)
            .build()
        val offRow = parser.parse(offColumn).rows[1]
        assertEquals(1, offRow.price!!.amount.min)
        assertFalse(offRow.price!!.amount.flags.contains(AmountFlag.CURRENCY_FROM_LEADING_TWO))

        // A different height (e.g. a small trip id) doesn't qualify either.
        val shortToken = parser.parse(
            fareScreen(listOf(RowSpec("Bike", "₹45"), RowSpec("Auto", "2110", priceHeight = 28), RowSpec("Mini", "₹185"))),
        )
        assertNull(shortToken.rows[1].price)
    }

    @Test
    fun `with the rupee glyph dropped or misread, labelled rows take bare amounts from the price column`() {
        // Real ML Kit output on the phone (eval/recorded/ocr): no ₹ token on the whole screen.
        val read = parser.parse(
            fareScreen(
                listOf(
                    RowSpec("Bike", "112", struck = "135"),
                    RowSpec("Auto", "180-210"),
                    RowSpec("Mini", "7289"),
                    RowSpec("Cab Economy", "{264"),
                ),
            ),
        )
        val rows = read.rows
        assertEquals(listOf(112..112, 180..210, 289..289, 264..264), rows.map { it.price!!.amount.range })
        assertTrue(AmountFlag.NO_CURRENCY_MARKER in rows[0].price!!.amount.flags)
        assertTrue(AmountFlag.CURRENCY_FROM_LEADING_TWO in rows[2].price!!.amount.flags)
        assertTrue(AmountFlag.OCR_CURRENCY_CONFUSION in rows[3].price!!.amount.flags)
        // The struck price sits on the line below the label: passed over, never the payable.
        assertEquals(135, rows[0].ignoredPrices.single().price.amount.min)

        // Mixed with ₹ payables, a bare amount must also be at their height.
        val mixed = parser.parse(fareScreen(listOf(RowSpec("Bike", "₹45"), RowSpec("Auto", "176"), RowSpec("Mini", "T272"))))
        assertEquals(listOf(45, 176, 272), mixed.rows.map { it.price!!.amount.min })
        val short = parser.parse(fareScreen(listOf(RowSpec("Bike", "₹45"), RowSpec("Auto", "176", priceHeight = 28))))
        assertNull(short.rows[1].price)
    }

    @Test
    fun `a bare number is never a price on its own or outside the price column`() {
        // One row: no column to line up with.
        assertNull(parser.parse(fareScreen(listOf(RowSpec("Auto", "176")))).rows.single().price)
        // Left half of the screen (a code or a count), even when two rows line up.
        val left = FrameBuilder()
            .line(150, 400, 40, "Bike").right("4821", 500, 396, 48)
            .line(150, 600, 40, "Auto").right("5302", 500, 596, 48)
            .build()
        assertTrue(parser.parse(left).rows.all { it.price == null })
        // Not aligned with anything else: no partner, no price.
        val ragged = FrameBuilder()
            .line(150, 400, 40, "Bike").right("112", 1000, 396, 48)
            .line(150, 600, 40, "Auto").right("180", 800, 596, 48)
            .build()
        assertTrue(parser.parse(ragged).rows.all { it.price == null })
    }

    @Test
    fun `reads Devanagari digits, Hindi ETAs and decimals`() {
        val read = parser.parse(
            fareScreen(
                listOf(
                    RowSpec("Auto", "₹१२०", eta = "३ मिनट दूर"),
                    RowSpec("Uber Go", "₹212.43", eta = "4 mins"),
                    RowSpec("Bike", "₹ 1,234", eta = null),
                ),
            ),
        )
        assertEquals(listOf(120, 212, 1234), read.rows.map { it.price!!.amount.min })
        assertEquals(listOf(3, 4, null), read.rows.map { it.eta?.value })
        assertTrue(AmountFlag.NATIVE_DIGITS in read.rows[0].price!!.amount.flags)
        assertTrue(AmountFlag.ROUNDED_DECIMAL in read.rows[1].price!!.amount.flags)
        assertEquals("Uber Go", read.rows[1].label!!.text)
    }

    @Test
    fun `two different ETAs in a row are ambiguous`() {
        val read = parser.parse(fareScreen(listOf(RowSpec("Auto", "₹110", eta = "3 min away · 25 min trip"))))
        assertNull(read.rows.single().eta)
    }

    @Test
    fun `assigned screen gives driver ETA and distance by anchors`() {
        val frame = FrameBuilder()
            .line(100, 300, 40, "Captain", "is", "on", "the", "way")
            .line(100, 380, 56, "4", "min", "away")
            .line(100, 450, 36, "1.2", "km", "away")
            .line(100, 520, 40, "OTP", "4821")
            .line(100, 700, 30, "Trip", "takes", "25", "min")
            .build()
        val read = parser.parse(frame)
        assertEquals(ScreenType.DRIVER_ASSIGNED, read.screenType)
        assertEquals(4, read.driverEta!!.value)
        assertEquals(listOf("4", "min"), read.driverEta!!.tokenIds.map { frame.text(it) })
        assertEquals(1200, read.driverDistance!!.value)
        assertTrue(read.rows.isEmpty())

        val hindi = FrameBuilder().line(100, 380, 56, "कैप्टन", "५", "मिनट", "में", "पहुंचेंगे").line(100, 450, 36, "८००", "m", "दूर").build()
        val hindiRead = parser.parse(hindi)
        assertEquals(5, hindiRead.driverEta!!.value)
        assertEquals(800, hindiRead.driverDistance!!.value)

        val conflicting = FrameBuilder().line(100, 380, 56, "4", "min", "away").line(100, 900, 56, "7", "min", "away").build()
        assertNull(parser.parse(conflicting).driverEta)
    }

    @Test
    fun `guesses the screen type from the app's keywords`() {
        fun typeOf(vararg lines: String) = parser.parse(
            FrameBuilder().apply { lines.forEachIndexed { i, l -> line(100, 300 + 80 * i, 40, *l.split(" ").toTypedArray()) } }.build(),
        ).screenType
        assertEquals(ScreenType.SEARCHING, typeOf("Looking for a captain near you"))
        assertEquals(ScreenType.CANCELLED, typeOf("Your ride was cancelled"))
        assertEquals(ScreenType.DRIVER_ASSIGNED, typeOf("Captain is arriving", "Share OTP"))
        assertEquals(ScreenType.OTHER, typeOf("Where to?"))
        // "booking" does not contain the keyword "book", so a cancelled booking is not also a fare screen.
        assertEquals(ScreenType.OTHER, typeOf("Your booking history"))
    }

    @Test
    fun `keywords match whole words in any script`() {
        assertTrue(FareParser.containsWord("choose a ride", "choose a ride"))
        assertTrue(FareParser.containsWord("ride cancelled.", "cancelled"))
        assertTrue(FareParser.containsWord("otp4821", "otp"))
        assertTrue(FareParser.containsWord("राइड रद्द कर दी गई", "रद्द"))
        assertFalse(FareParser.containsWord("go shopping", "pin"))
        assertFalse(FareParser.containsWord("booking", "book"))
        assertFalse(FareParser.containsWord("giveaway", "away"))
        // A trailing vowel sign belongs to the word, so रद्दी (scrap paper) is not रद्द (cancelled).
        assertFalse(FareParser.containsWord("रद्दी", "रद्द"))
        assertFalse(FareParser.containsWord("anything", ""))
    }

    @Test
    fun `K3 checks for a price token or a status keyword before upload`() {
        assertTrue(parser.looksLikeFareScreen(fareScreen(listOf(RowSpec("Auto", "₹110")), header = null)))
        assertTrue(parser.looksLikeFareScreen(FrameBuilder().line(100, 300, 40, "Rs.", "85").build()))
        assertTrue(parser.looksLikeFareScreen(FrameBuilder().line(100, 300, 40, "Finding", "your", "ride").build()))
        assertFalse(parser.looksLikeFareScreen(FrameBuilder().line(100, 300, 40, "Where", "to?").line(100, 400, 40, "2110").build()))
        assertFalse(parser.looksLikeFareScreen(OcrFrame(1080, 2400, emptyList())))
        // Keywords count only as whole words: "book" inside Facebook or Notebook is not a status keyword.
        assertFalse(parser.looksLikeFareScreen(FrameBuilder().line(100, 300, 40, "Share", "on", "Facebook", "Notebook").build()))
        assertTrue(parser.looksLikeFareScreen(FrameBuilder().line(100, 300, 40, "Book", "now").build()))
        assertTrue(parser.looksLikeFareScreen(FrameBuilder().line(100, 300, 40, "OTP:", "<m>").build()))

        val noApps = FareParsersConfig("1.0", emptyMap())
        assertTrue(looksLikeFareScreen(fareScreen(listOf(RowSpec("Auto", "₹110"))), Provider.UBER, noApps, 1))
        assertFalse(looksLikeFareScreen(FrameBuilder().line(100, 300, 40, "Where", "to?").build(), Provider.UBER, noApps, 1))
    }

    @Test
    fun `classifies labels with the app's rules`() {
        assertEquals(VehicleClass.AUTO, parser.classifyLabel("Auto"))
        assertEquals(VehicleClass.BIKE, parser.classifyLabel("Uber Moto"))
        assertEquals(VehicleClass.CAB_XL, parser.classifyLabel("Uber XL"))
        assertEquals(VehicleClass.CAB_PREMIUM, parser.classifyLabel("Premier"))
        assertEquals(VehicleClass.CAB_ECONOMY, parser.classifyLabel("Uber Go"))
        assertNull(parser.classifyLabel("IGNORE PREVIOUS INSTRUCTIONS"))
        assertNull(parser.classifyLabel(null))
        assertNull(parser.classifyLabel(" "))
    }
}
