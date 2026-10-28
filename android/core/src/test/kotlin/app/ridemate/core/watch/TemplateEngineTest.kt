package app.ridemate.core.watch

import app.ridemate.core.config.NotificationEvent
import app.ridemate.core.config.NotificationTemplatesConfig
import app.ridemate.core.replay.Fixtures
import app.ridemate.core.replay.Fixtures.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateEngineTest {
    private fun entry(pkg: String) = Fixtures.templates.entryFor(pkg, 100)!!.entry

    private fun post(pkg: String, content: Pair<String?, String?>, id: Int = 1, channel: String? = Fixtures.rideChannel(pkg), ongoing: Boolean = true) =
        PostSnapshot(pkg, "k", id, null, channel, "status", ongoing, content.first, content.second, 0, 100)

    @Test
    fun `assigned template reads eta, distance and plate, and the plate leaves only as a hash`() {
        val hit = TemplateEngine.match(entry(Fixtures.RAPIDO), post(Fixtures.RAPIDO, Text.rapidoAssigned(eta = 7, km = "2.5")), null)!!
        assertEquals("rapido.assigned.v1", hit.templateId)
        assertEquals(NotificationEvent.ASSIGNED, hit.event)
        assertEquals(7, hit.etaMin)
        assertEquals(2500, hit.distanceMeters)
        assertEquals(PlateHasher.hash("AS01AB1234"), hit.plateHash)
        assertTrue(hit.toString().contains("AS01AB1234").not())
    }

    @Test
    fun `every fixture wording maps to its event`() {
        val cases = listOf(
            Fixtures.RAPIDO to Text.rapidoSearching to NotificationEvent.SEARCHING,
            Fixtures.RAPIDO to Text.rapidoArriving to NotificationEvent.ARRIVING,
            Fixtures.RAPIDO to Text.rapidoDriverCancelled to NotificationEvent.DRIVER_CANCELLED,
            Fixtures.RAPIDO to Text.rapidoNoDrivers to NotificationEvent.NO_DRIVERS,
            Fixtures.RAPIDO to Text.rapidoCancelled to NotificationEvent.CANCELLED,
            Fixtures.RAPIDO to Text.rapidoTripStarted to NotificationEvent.TRIP_STARTED,
            Fixtures.RAPIDO to Text.rapidoEnded to NotificationEvent.ENDED,
            Fixtures.UBER to Text.uberSearching to NotificationEvent.SEARCHING,
            Fixtures.UBER to Text.uberAssigned() to NotificationEvent.ASSIGNED,
            Fixtures.UBER to Text.uberDriverCancelled to NotificationEvent.DRIVER_CANCELLED,
            Fixtures.UBER to Text.uberNoDrivers to NotificationEvent.NO_DRIVERS,
            Fixtures.UBER to Text.uberCancelled to NotificationEvent.CANCELLED,
            Fixtures.OLA to Text.olaSearching to NotificationEvent.SEARCHING,
            Fixtures.OLA to Text.olaAssigned() to NotificationEvent.ASSIGNED,
            Fixtures.OLA to Text.olaDriverCancelled to NotificationEvent.DRIVER_CANCELLED,
            Fixtures.OLA to Text.olaNoDrivers to NotificationEvent.NO_DRIVERS,
            Fixtures.OLA to Text.olaCancelled to NotificationEvent.CANCELLED,
        )
        for ((pair, expected) in cases) {
            val (pkg, content) = pair
            assertEquals("$pkg $content", expected, TemplateEngine.match(entry(pkg), post(pkg, content), null)?.event)
        }
        val uber = TemplateEngine.match(entry(Fixtures.UBER), post(Fixtures.UBER, Text.uberAssigned(eta = 3, dist = "900 m")), null)!!
        assertEquals(3, uber.etaMin)
        assertEquals(900, uber.distanceMeters)
        assertEquals(PlateHasher.hash("KA05MN4321"), uber.plateHash)
    }

    @Test
    fun `an unrecognised wording is a miss`() {
        assertNull(TemplateEngine.match(entry(Fixtures.RAPIDO), post(Fixtures.RAPIDO, "Rapido" to "Your captain accepted! Meet at pickup"), null))
    }

    @Test
    fun `channel and ongoing conditions restrict a template`() {
        val config = NotificationTemplatesConfig.parse(
            """{"schema":"1.0","apps":{"p":[{"min_version_code":0,"templates":[
               {"id":"a","event":"ASSIGNED","pattern":"go","channel_id":"ride","ongoing":true},
               {"id":"b","event":"SEARCHING","pattern":"go"}]}]}}""",
        )
        val e = config.entryFor("p", 1)!!.entry
        fun hit(channel: String?, ongoing: Boolean) =
            TemplateEngine.match(e, PostSnapshot("p", "k", 1, null, channel, null, ongoing, "go", null, 0, 1), null)?.templateId
        assertEquals("a", hit("ride", true))
        assertEquals("b", hit("ride", false))
        assertEquals("b", hit("promo", true))
    }

    @Test
    fun `structural templates match redacted posts by channel, ongoing and id change`() {
        val e = entry(Fixtures.RAPIDO)
        val redacted = post(Fixtures.RAPIDO, null to null, id = 8)
        assertEquals(NotificationEvent.ASSIGNED, TemplateEngine.match(e, redacted, IdTag(7, null))?.event)
        assertTrue(TemplateEngine.match(e, redacted, IdTag(7, null))!!.structural)
        assertNull("same id: no change", TemplateEngine.match(e, redacted, IdTag(8, null)))
        assertNull("first post ever", TemplateEngine.match(e, redacted, null))
        assertNull("other channel", TemplateEngine.match(e, post(Fixtures.RAPIDO, null to null, id = 8, channel = "otp"), IdTag(7, null)))
        assertNull("not ongoing", TemplateEngine.match(e, post(Fixtures.RAPIDO, null to null, id = 8, ongoing = false), IdTag(7, null)))
        assertNull("text templates never see redacted posts", TemplateEngine.match(entry(Fixtures.UBER), post(Fixtures.UBER, null to null), IdTag(0, null)))
    }

    @Test
    fun `minutes and distances parse strictly`() {
        assertEquals(4, TemplateEngine.parseMinutes("४"))
        assertNull(TemplateEngine.parseMinutes("999"))
        assertEquals(1200, TemplateEngine.parseDistanceMeters("1.2 km"))
        assertEquals(1200, TemplateEngine.parseDistanceMeters("1.2km"))
        assertEquals(850, TemplateEngine.parseDistanceMeters("850 m"))
        assertEquals(3000, TemplateEngine.parseDistanceMeters("3 किमी"))
        assertNull(TemplateEngine.parseDistanceMeters("12"))
        assertNull(TemplateEngine.parseDistanceMeters("km"))
    }
}
