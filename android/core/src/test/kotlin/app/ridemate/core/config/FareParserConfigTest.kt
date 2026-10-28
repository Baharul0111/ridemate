package app.ridemate.core.config

import app.ridemate.core.fare.FareParser
import app.ridemate.core.fare.TestConfigs
import app.ridemate.core.model.Provider
import app.ridemate.core.model.ScreenType
import app.ridemate.core.model.VehicleClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FareParserConfigTest {
    /** The contracts §2.2 example, verbatim. */
    private val contractExample = """
        {"schema": "1.0",
         "apps": {
          "com.ubercab": [
           {"min_version_code": 0,
            "vehicle_labels": [{"pattern": "(?i)^uber ?moto|bike", "vehicle_class": "bike"},
                               {"pattern": "(?i)auto", "vehicle_class": "auto"}],
            "price_token_pattern": "(?i)(?:₹|rs\\.?|inr)\\s*[0-9०-९][0-9०-९,]*(?:\\s*[-–]\\s*(?:₹|rs\\.?)?\\s*[0-9०-९][0-9०-९,]*)?",
            "eta_pattern": "(?i)(\\d+)\\s*(?:min|mins|मिनट)",
            "strike_height_ratio": 0.8,
            "row_gap_ratio": 1.8,
            "driver_eta_anchors": ["away", "arriving", "pickup", "मिनट", "दूर"],
            "driver_distance_pattern": "(?i)(\\d+(?:\\.\\d+)?)\\s*(km|m)\\b",
            "screen_keywords": {"fare_options": ["choose a ride", "book"], "searching": ["looking for", "finding"],
                                "driver_assigned": ["arriving", "on the way", "otp"], "cancelled": ["cancelled"]}}
          ]}}
    """.trimIndent()

    @Test
    fun `parses the contracts example`() {
        val config = parseFareParsers(contractExample)
        val entry = config.entryFor("com.ubercab", 4_000_000)!!.entry
        assertEquals(2, entry.vehicleLabels.size)
        assertEquals(VehicleClass.BIKE, entry.vehicleLabels[0].vehicleClass)
        assertEquals(0.8, entry.strikeHeightRatio, 0.0)
        assertEquals(listOf("arriving", "on the way", "otp"), entry.keywordsByScreen[ScreenType.DRIVER_ASSIGNED])
        assertNull(config.entryFor("com.olacabs.customer", 1))
        val parser = FareParser.forApp(config, Provider.UBER, 1)!!
        assertEquals(VehicleClass.BIKE, parser.classifyLabel("Uber Moto"))
        assertNull(FareParser.forApp(config, Provider.RAPIDO, 1))
    }

    @Test
    fun `rejects patterns RE2J cannot compile`() {
        for (bad in listOf("(a)\\\\1", "(?=lookahead)", "(?<=x)y", "[unclosed", "a{1001}")) {
            val raw = contractExample.replace("(?i)auto", bad)
            assertThrows(bad, IllegalArgumentException::class.java) { parseFareParsers(raw) }
        }
        val badPrice = contractExample.replace("\"price_token_pattern\": \"", "\"price_token_pattern\": \"(?<!x)")
        assertThrows(IllegalArgumentException::class.java) { parseFareParsers(badPrice) }
    }

    @Test
    fun `validates ratios, schema and structure`() {
        assertThrows(IllegalArgumentException::class.java) { parseFareParsers(contractExample.replace("\"strike_height_ratio\": 0.8", "\"strike_height_ratio\": 1.5")) }
        assertThrows(IllegalArgumentException::class.java) { parseFareParsers(contractExample.replace("\"row_gap_ratio\": 1.8", "\"row_gap_ratio\": 0")) }
        assertThrows(IllegalArgumentException::class.java) { parseFareParsers(contractExample.replace("\"schema\": \"1.0\"", "\"schema\": \"2.0\"")) }
        assertThrows(Exception::class.java) { parseFareParsers(contractExample.replace("\"vehicle_class\": \"auto\"", "\"vehicle_class\": \"rocket\"")) }
        assertThrows(Exception::class.java) { parseFareParsers("[]") }
        assertThrows(IllegalArgumentException::class.java) { parseFareParsers("""{"schema": "1.0", "apps": {"com.ubercab": []}}""") }
        // Unknown screen keyword keys and unknown fields are additive changes and are ignored.
        val additive = parseFareParsers(contractExample.replace("\"cancelled\": [\"cancelled\"]", "\"cancelled\": [\"cancelled\"], \"trip_started\": [\"enjoy\"]"))
        assertEquals(4, additive.apps.getValue("com.ubercab").single().keywordsByScreen.size)
    }

    @Test
    fun `layered resolution falls back from a broken remote payload`() {
        val broken = contractExample.replace("(?i)auto", "(?=x)")
        val layered = resolveLayered(broken, lastGood = contractExample, bundled = TestConfigs.FARE_PARSERS_JSON, parse = ::parseFareParsers)
        assertEquals(Layer.LAST_GOOD, layered.source)
        val bundled = resolveLayered("""{"schema": "2.0", "apps": {}}""", null, TestConfigs.FARE_PARSERS_JSON, ::parseFareParsers)
        assertEquals(Layer.BUNDLED, bundled.source)
        assertNotNull(bundled.value.entryFor("demo.kestrel", 1))
        assertEquals(Layer.REMOTE, resolveLayered(contractExample, null, TestConfigs.FARE_PARSERS_JSON, ::parseFareParsers).source)
    }

    @Test
    fun `newest entry at or below the installed version wins`() {
        val versioned = contractExample.replace(
            "\"com.ubercab\": [",
            "\"com.ubercab\": [{\"min_version_code\": 5000, \"max_known_version_code\": 5100, " +
                "\"vehicle_labels\": [{\"pattern\": \"(?i)new\", \"vehicle_class\": \"auto\"}], " +
                "\"price_token_pattern\": \"₹\\\\d+\", \"eta_pattern\": \"(\\\\d+) min\", \"driver_distance_pattern\": \"(\\\\d+) km\"},",
        )
        val config = parseFareParsers(versioned)
        assertEquals(0L, config.entryFor("com.ubercab", 4_999)!!.entry.minVersionCode)
        val current = config.entryFor("com.ubercab", 5_050)!!
        assertEquals(5000L, current.entry.minVersionCode)
        assertEquals(false, current.isFallback)
        assertTrue(config.entryFor("com.ubercab", 6_000)!!.isFallback)
    }
}
