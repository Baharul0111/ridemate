package app.ridemate.launch

import app.ridemate.core.config.LaunchMode
import app.ridemate.core.config.parseLaunchTemplates
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Provider
import app.ridemate.trip.TripPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPlannerTest {
    private val pickup = TripPlace("Barpeta Road", LatLng(26.5032, 90.9757))
    private val drop = TripPlace("LGBI Airport, Guwahati & T1", LatLng(26.1061, 91.5859))
    private val templates = LaunchPlanner.DEFAULT_TEMPLATES

    @Test
    fun `the built-in default is the contracts launch_templates payload`() {
        val uber = templates.templateFor(Provider.UBER.packageName)!!
        assertEquals(LaunchMode.URI, uber.mode)
        assertEquals(LaunchMode.LAUNCH, uber.fallback)
        assertTrue(uber.uri!!.startsWith("https://m.uber.com/ul/?action=setPickup"))
        assertEquals(LaunchMode.LAUNCH_PASTE, templates.templateFor(Provider.OLA.packageName)!!.mode)
        assertEquals(LaunchMode.LAUNCH_PASTE, templates.templateFor(Provider.RAPIDO.packageName)!!.mode)
    }

    @Test
    fun `uber gets the universal link with encoded coordinates and nicknames`() {
        val plan = LaunchPlanner.plan(Provider.UBER, templates, pickup, drop) as LaunchPlan.DeepLink
        assertEquals(
            "https://m.uber.com/ul/?action=setPickup" +
                "&pickup[latitude]=26.503200&pickup[longitude]=90.975700&pickup[nickname]=Barpeta%20Road" +
                "&dropoff[latitude]=26.106100&dropoff[longitude]=91.585900" +
                "&dropoff[nickname]=LGBI%20Airport%2C%20Guwahati%20%26%20T1",
            plan.uri,
        )
        assertNull(plan.fallbackPasteDrop) // fallback "launch" = plain launch
    }

    @Test
    fun `hindi nicknames are percent-encoded as UTF-8`() {
        val hindiDrop = TripPlace("गुवाहाटी हवाई अड्डा", LatLng(26.1061, 91.5859))
        val plan = LaunchPlanner.plan(Provider.UBER, templates, pickup, hindiDrop) as LaunchPlan.DeepLink
        val nickname = plan.uri.substringAfter("dropoff[nickname]=")
        assertTrue(nickname.startsWith("%E0%A4%97"))
        assertFalse(nickname.contains(' '))
        assertEquals("गुवाहाटी हवाई अड्डा", java.net.URLDecoder.decode(nickname, "UTF-8"))
    }

    @Test
    fun `the uber link never carries a client_id, even when a remote template adds one`() {
        assertFalse(LaunchPlanner.plan(Provider.UBER, templates, pickup, drop).let { (it as LaunchPlan.DeepLink).uri }.contains("client_id"))
        val withClientId = parseLaunchTemplates(
            """{"schema":"1.0","apps":{"com.ubercab":{"mode":"uri","fallback":"launch",
              "uri":"https://m.uber.com/ul/?client_id=abc123&action=setPickup&pickup[latitude]={plat}&pickup[longitude]={plng}&dropoff[latitude]={dlat}&dropoff[longitude]={dlng}&dropoff[nickname]={dname}&CLIENT_ID=x"}}}""",
        )
        val plan = LaunchPlanner.plan(Provider.UBER, withClientId, pickup, drop) as LaunchPlan.DeepLink
        assertFalse(plan.uri.lowercase().contains("client_id"))
        assertTrue(plan.uri.startsWith("https://m.uber.com/ul/?action=setPickup&pickup[latitude]=26.503200"))
        assertTrue(plan.uri.endsWith("dropoff[nickname]=LGBI%20Airport%2C%20Guwahati%20%26%20T1"))
    }

    @Test
    fun `withoutClientId keeps everything else byte for byte`() {
        assertEquals("https://x.test/a?b=1&c=2#f", LaunchPlanner.withoutClientId("https://x.test/a?client_id=9&b=1&c=2#f"))
        assertEquals("https://x.test/a#f", LaunchPlanner.withoutClientId("https://x.test/a?client_id=9#f"))
        assertEquals("https://x.test/a", LaunchPlanner.withoutClientId("https://x.test/a"))
        assertEquals("https://x.test/a?client_idx=1", LaunchPlanner.withoutClientId("https://x.test/a?client_idx=1"))
        assertEquals("https://x.test/a?b=1", LaunchPlanner.withoutClientId("https://x.test/a?b=1&Client_Id"))
    }

    @Test
    fun `without coordinates uber falls back to the configured plain launch`() {
        val typedDrop = TripPlace("Near the old temple, Barpeta", null)
        assertEquals(LaunchPlan.Launch(Provider.UBER, null), LaunchPlanner.plan(Provider.UBER, templates, pickup, typedDrop))
        val typedPickup = TripPlace("Hostel 3", null)
        assertEquals(LaunchPlan.Launch(Provider.UBER, null), LaunchPlanner.plan(Provider.UBER, templates, typedPickup, drop))
    }

    @Test
    fun `a launch_paste fallback offers the drop`() {
        val config = parseLaunchTemplates(
            """{"schema":"1.0","apps":{"com.ubercab":{"mode":"uri","fallback":"launch_paste",
              "uri":"https://m.uber.com/ul/?action=setPickup&dropoff[latitude]={dlat}&dropoff[longitude]={dlng}"}}}""",
        )
        val deep = LaunchPlanner.plan(Provider.UBER, config, pickup, drop) as LaunchPlan.DeepLink
        assertEquals(drop.label, deep.fallbackPasteDrop)
        val typed = TripPlace("Typed place", null)
        assertEquals(LaunchPlan.Launch(Provider.UBER, "Typed place"), LaunchPlanner.plan(Provider.UBER, config, pickup, typed))
    }

    @Test
    fun `ola and rapido launch plainly with the drop for the paste chip`() {
        assertEquals(LaunchPlan.Launch(Provider.OLA, drop.label), LaunchPlanner.plan(Provider.OLA, templates, pickup, drop))
        assertEquals(LaunchPlan.Launch(Provider.RAPIDO, drop.label), LaunchPlanner.plan(Provider.RAPIDO, templates, pickup, drop))
        val typed = TripPlace("Typed", null)
        assertEquals(LaunchPlan.Launch(Provider.OLA, "Typed"), LaunchPlanner.plan(Provider.OLA, templates, pickup, typed))
    }

    @Test
    fun `plain launch mode and a provider missing from config`() {
        val config = parseLaunchTemplates("""{"schema":"1.0","apps":{"com.olacabs.customer":{"mode":"launch"}}}""")
        assertEquals(LaunchPlan.Launch(Provider.OLA, null), LaunchPlanner.plan(Provider.OLA, config, pickup, drop))
        assertEquals(LaunchPlan.Launch(Provider.RAPIDO, drop.label), LaunchPlanner.plan(Provider.RAPIDO, config, pickup, drop))
        assertEquals(LaunchPlan.Launch(Provider.UBER, drop.label), LaunchPlanner.plan(Provider.UBER, config, pickup, drop))
    }
}
