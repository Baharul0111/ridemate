package app.ridemate.core.config

import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Place
import app.ridemate.core.model.VehicleClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** sanity_bands, cancel_cards and launch_templates (contracts §2.3, §2.4, §2.6). */
class FareSideConfigTest {
    private val bandsJson = """
        {"schema": "1.0", "price_min_inr": 15, "price_max_inr": 5000, "eta_max_min": 60,
         "road_factor": 1.35, "base_allowance_inr": 60,
         "per_km_inr": {"bike": [3, 30], "auto": [8, 45], "cab_economy": [8, 60], "cab_premium": [10, 90],
                        "cab_xl": [12, 110], "other": [3, 150]}}
    """.trimIndent()

    private val cardsJson = """
        {"schema": "1.0", "apps": {"com.ubercab": {"steps": {"en": ["Tap **Cancel ride**", "Choose **Booked another ride**", "Tap **Confirm**"],
          "hi": ["**Cancel ride** पर टैप करें", "**Booked another ride** चुनें", "**Confirm** पर टैप करें"]},
          "point_target": "cancel_button", "cancelled_keywords": ["cancelled", "रद्द"]}}}
    """.trimIndent()

    private val launchJson = """
        {"schema": "1.0", "apps": {
         "com.ubercab": {"mode": "uri", "uri": "https://m.uber.com/ul/?action=setPickup&pickup[latitude]={plat}&pickup[longitude]={plng}&pickup[nickname]={pname}&dropoff[latitude]={dlat}&dropoff[longitude]={dlng}&dropoff[nickname]={dname}", "fallback": "launch"},
         "com.olacabs.customer": {"mode": "launch_paste"},
         "com.rapido.passenger": {"mode": "launch_paste"}}}
    """.trimIndent()

    @Test
    fun `sanity bands parse and validate`() {
        val bands = parseSanityBands(bandsJson)
        assertEquals(SanityBandsConfig.DEFAULT, bands)
        assertEquals(8.0..45.0, bands.perKm(VehicleClass.AUTO))
        val noXl = parseSanityBands(bandsJson.replace("\"cab_xl\": [12, 110], ", ""))
        assertEquals(3.0..150.0, noXl.perKm(VehicleClass.CAB_XL))
        for (broken in listOf(
            bandsJson.replace("[8, 45]", "[45, 8]"),
            bandsJson.replace("\"other\": [3, 150]", "\"misc\": [3, 150]"),
            bandsJson.replace("\"road_factor\": 1.35", "\"road_factor\": 0.5"),
            bandsJson.replace("\"price_min_inr\": 15", "\"price_min_inr\": 6000"),
            bandsJson.replace("[3, 30]", "[3]"),
            bandsJson.replace("\"schema\": \"1.0\"", "\"schema\": \"3.1\""),
        )) {
            assertThrows(broken, IllegalArgumentException::class.java) { parseSanityBands(broken) }
        }
    }

    @Test
    fun `cancel cards give bold segments with an English fallback`() {
        val card = parseCancelCards(cardsJson).cardFor("com.ubercab")!!
        assertEquals("cancel_button", card.pointTarget)
        val first = card.stepsFor("en").first()
        assertEquals(listOf(CancelStep.Segment("Tap ", false), CancelStep.Segment("Cancel ride", true)), first.segments)
        assertEquals("Tap Cancel ride", first.plainText)
        assertEquals("Cancel ride पर टैप करें", card.stepsFor("hi").first().plainText)
        assertEquals(card.stepsFor("en"), card.stepsFor("bn"))
        assertNull(parseCancelCards(cardsJson).cardFor("com.olacabs.customer"))
        assertThrows(IllegalArgumentException::class.java) { parseCancelCards(cardsJson.replace("\"en\":", "\"fr\":")) }
        assertThrows(IllegalArgumentException::class.java) { parseCancelCards(cardsJson.replace("Tap **Confirm**", "Tap **Confirm")) }
        assertThrows(IllegalArgumentException::class.java) { parseCancelCards(cardsJson.replace("Tap **Confirm**", " ")) }
    }

    @Test
    fun `launch templates fill and encode the route`() {
        val templates = parseLaunchTemplates(launchJson)
        val uber = templates.templateFor("com.ubercab")!!
        assertEquals(LaunchMode.URI, uber.mode)
        assertEquals(LaunchMode.LAUNCH, uber.fallback)
        val uri = uber.buildUri(
            Place("Paltan Bazaar, Guwahati", LatLng(26.1817, 91.751)),
            Place("LGBI Airport & Terminal 2", LatLng(26.1061, 91.5859)),
        )!!
        assertEquals(
            "https://m.uber.com/ul/?action=setPickup&pickup[latitude]=26.181700&pickup[longitude]=91.751000" +
                "&pickup[nickname]=Paltan%20Bazaar%2C%20Guwahati&dropoff[latitude]=26.106100&dropoff[longitude]=91.585900" +
                "&dropoff[nickname]=LGBI%20Airport%20%26%20Terminal%202",
            uri,
        )
        assertEquals(LaunchMode.LAUNCH_PASTE, templates.templateFor("com.olacabs.customer")!!.mode)
        assertNull(templates.templateFor("com.olacabs.customer")!!.buildUri(Place("a", LatLng(0.0, 0.0)), Place("b", LatLng(0.0, 0.0))))
        val hindi = uber.buildUri(Place("पलटन बाज़ार", LatLng(26.0, 91.0)), Place("x", LatLng(26.1, 91.1)))!!
        assertTrue(hindi, hindi.contains("pickup[nickname]=%E0%A4%AA"))

        assertThrows(IllegalArgumentException::class.java) { parseLaunchTemplates(launchJson.replace("{dname}", "{secret}")) }
        assertThrows(IllegalArgumentException::class.java) { parseLaunchTemplates(launchJson.replace("https://m.uber", "http://m.uber")) }
        assertThrows(IllegalArgumentException::class.java) { parseLaunchTemplates(launchJson.replace("\"fallback\": \"launch\"", "\"fallback\": \"uri\"")) }
        assertThrows(IllegalArgumentException::class.java) {
            parseLaunchTemplates("""{"schema": "1.0", "apps": {"com.ubercab": {"mode": "uri"}}}""")
        }
    }

    @Test
    fun `an unknown launch mode leaves only that app out`() {
        // The bundled defaults add "demo" entries; a newer MINOR mode must not take Uber's deep link down with it.
        val withNewModes = launchJson.replace(
            "\"com.olacabs.customer\": {\"mode\": \"launch_paste\"}",
            "\"com.olacabs.customer\": {\"mode\": \"launch_paste\", \"fallback\": \"intent_v2\"}, " +
                "\"demo.kestrel\": {\"mode\": \"demo\", \"asset_dir\": \"demo/kestrel\"}",
        )
        val templates = parseLaunchTemplates(withNewModes)
        assertEquals(setOf("com.ubercab", "com.olacabs.customer", "com.rapido.passenger"), templates.apps.keys)
        assertNull(templates.templateFor("demo.kestrel"))
        assertEquals(LaunchTemplate(LaunchMode.LAUNCH_PASTE), templates.templateFor("com.olacabs.customer"))
        assertEquals(LaunchMode.URI, templates.templateFor("com.ubercab")!!.mode)
        // Known modes are still validated, and a missing or non-string mode still rejects the payload.
        assertThrows(IllegalArgumentException::class.java) {
            parseLaunchTemplates(withNewModes.replace("https://m.uber", "http://m.uber"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseLaunchTemplates("""{"schema": "1.0", "apps": {"com.ubercab": {"uri": "https://m.uber.com"}}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseLaunchTemplates("""{"schema": "1.0", "apps": {"com.ubercab": {"mode": 1}}}""")
        }
    }
}
