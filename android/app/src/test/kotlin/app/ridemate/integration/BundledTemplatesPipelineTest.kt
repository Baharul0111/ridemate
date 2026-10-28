package app.ridemate.integration

import app.ridemate.core.config.NotificationEvent
import app.ridemate.core.config.NotificationTemplatesConfig
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.state.ProviderUpdate
import app.ridemate.core.state.RideEvent
import app.ridemate.core.time.Clock
import app.ridemate.core.watch.Disposition
import app.ridemate.core.watch.LegDirectory
import app.ridemate.core.watch.LegView
import app.ridemate.core.watch.PlateHasher
import app.ridemate.core.watch.PostSnapshot
import app.ridemate.core.watch.PostSource
import app.ridemate.core.watch.RideWatchPipeline
import app.ridemate.core.watch.TemplateEngine
import app.ridemate.demo.BundledAssets
import app.ridemate.demo.DemoAction
import app.ridemate.watch.PiiTextMasker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The APK's bundled `notification_templates` and the demo `script.json` through the **real core RideWatch pipeline**
 * with the production masker: every scripted post (all three tracks) and the live-app samples of
 * `config/tests/test_patterns.py` produce exactly the expected RideEvent, and when several templates match, the first
 * one in list order wins (the config builder orders specific events — driver cancelled, no drivers — first).
 */
class BundledTemplatesPipelineTest {
    private val templates: NotificationTemplatesConfig = BundledAssets.templates

    private class Recorder : LegDirectory {
        val events = mutableListOf<Pair<String, RideEvent>>()
        override val activeSessionId: String = "s-bundled"
        override fun legFor(packageName: String): LegView = LegView("leg:$packageName", RideState.SEARCHING, 1, isBooked = true)
    }

    private val clock = object : Clock {
        var now = 1_000L
        override fun elapsedMs(): Long = now
        override fun wallMs(): Long = 1_793_000_000_000L + now
    }

    private fun pipeline(scope: kotlinx.coroutines.CoroutineScope, legs: Recorder) = RideWatchPipeline(
        clock = clock,
        scope = scope,
        legs = legs,
        sink = { legId, event ->
            legs.events += legId to event
            Disposition.TRANSITION
        },
        templates = { templates },
        classifier = null,
        masker = PiiTextMasker(),
    ).apply { demoSessionActive = true }

    /** The first template of [post]'s app (version entry) whose filters and regex accept it: the one that must win. */
    private fun firstMatchId(post: PostSnapshot): String? {
        val entry = templates.entryFor(post.packageName, post.versionCode)?.entry ?: return null
        return entry.templates.firstOrNull { t ->
            (t.spec.channelId == null || t.spec.channelId == post.channelId) &&
                (t.spec.ongoing == null || t.spec.ongoing == post.isOngoing) &&
                t.regex.matcher(post.matchText).find()
        }?.spec?.id
    }

    @Test
    fun `every scripted demo post of every track becomes the expected event, first template in list order`() = runTest {
        val script = BundledAssets.script
        var checked = 0
        for (track in script.tracks) {
            val legs = Recorder()
            val p = pipeline(backgroundScope, legs)
            track.events.filter { it.action == DemoAction.POST }.forEachIndexed { i, event ->
                clock.now += 1_000
                val snapshot = event.toSnapshot(clock.wallMs(), script.providers.getValue(event.packageName).versionCode)
                    .copy(sbnKey = "${event.sbnKey}#${track.id}#$i")
                val before = legs.events.size
                p.onPosted(PostSource.of(snapshot))
                val update = legs.events.drop(before).singleOrNull()?.second as? ProviderUpdate
                assertNotNull("${track.id}/${event.id} produced one template event", update)
                assertEquals("${track.id}/${event.id}", event.expectEvent, update!!.signal)
                assertEquals("${track.id}/${event.id}: first match in list order wins", firstMatchId(snapshot), update.templateId)
                event.expectGroups["eta"]?.let { assertEquals(it.toInt(), update.etaMin) }
                event.expectGroups["dist"]?.let { assertEquals(TemplateEngine.parseDistanceMeters(it), update.distanceMeters) }
                event.expectGroups["plate"]?.let { assertEquals(PlateHasher.hash(it), update.plateHash) }
                checked++
            }
        }
        assertTrue("the script has posts on all three tracks", checked >= 20)
    }

    @Test
    fun `live ride-app samples hit the bundled templates with precedence (driver cancelled before cancelled)`() = runTest {
        val legs = Recorder()
        val p = pipeline(backgroundScope, legs)
        val samples = listOf(
            Triple(Provider.UBER, "Uber" to "Looking for nearby drivers", NotificationEvent.SEARCHING),
            Triple(Provider.UBER, "Your driver is on the way" to "Ravi is in a white hatchback", NotificationEvent.ASSIGNED),
            Triple(Provider.UBER, "Your driver has arrived" to "Meet at the pickup point", NotificationEvent.ARRIVING),
            Triple(Provider.UBER, "Driver cancelled" to "We're finding you a new driver", NotificationEvent.DRIVER_CANCELLED),
            Triple(Provider.UBER, "Trip cancelled" to "Your trip was cancelled", NotificationEvent.CANCELLED),
            Triple(Provider.UBER, "Ride cancelled by driver" to "Requesting again", NotificationEvent.DRIVER_CANCELLED),
            Triple(Provider.UBER, "Thanks for riding" to "Rate your trip", NotificationEvent.ENDED),
            Triple(Provider.OLA, "Ola" to "Finding a cab near you", NotificationEvent.SEARCHING),
            Triple(Provider.OLA, "Ola" to "Your driver will reach in 6 mins", NotificationEvent.ASSIGNED),
            Triple(Provider.OLA, "Booking cancelled" to "Your booking has been cancelled", NotificationEvent.CANCELLED),
            Triple(Provider.OLA, "Ola" to "कोई कैब उपलब्ध नहीं है", NotificationEvent.NO_DRIVERS),
            Triple(Provider.RAPIDO, "Rapido" to "Looking for captains near you", NotificationEvent.SEARCHING),
            Triple(Provider.RAPIDO, "Rapido" to "Captain Ravi is on the way, 4 min", NotificationEvent.ASSIGNED),
            Triple(Provider.RAPIDO, "Your captain has arrived" to "Share the OTP", NotificationEvent.ARRIVING),
            Triple(Provider.RAPIDO, "Captain cancelled" to "Finding you another captain", NotificationEvent.DRIVER_CANCELLED),
            Triple(Provider.RAPIDO, "Ride cancelled" to "Your ride has been cancelled", NotificationEvent.CANCELLED),
            Triple(Provider.RAPIDO, "राइड रद्द" to "आपकी राइड रद्द कर दी गई है", NotificationEvent.CANCELLED),
            Triple(Provider.RAPIDO, "Ride completed" to "Pay the captain", NotificationEvent.ENDED),
        )
        samples.forEachIndexed { i, (provider, post, expected) ->
            clock.now += 1_000
            val snapshot = PostSnapshot(
                packageName = provider.packageName, sbnKey = "0|${provider.packageName}|$i", id = i, tag = null,
                channelId = null, category = null, isOngoing = false, title = post.first, text = post.second,
                postTimeMs = clock.wallMs(), versionCode = 1L,
            )
            val before = legs.events.size
            p.onPosted(PostSource.of(snapshot))
            val update = legs.events.drop(before).single().second as ProviderUpdate
            assertEquals("${provider.name}: ${post.first}", expected, update.signal)
            assertEquals("${provider.name}: ${post.first}: first match wins", firstMatchId(snapshot), update.templateId)
        }
    }
}
