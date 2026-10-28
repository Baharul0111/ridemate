package app.ridemate.demo

import app.ridemate.core.config.NotificationEvent
import app.ridemate.core.model.Provider
import app.ridemate.core.watch.PlateHasher
import app.ridemate.core.watch.RemovalReason
import app.ridemate.core.watch.TemplateEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bundled `script.json` parses, validates, and agrees with the bundled demo notification templates. */
class DemoScriptTest {
    private val script = BundledAssets.script

    @Test
    fun `bundled script has three fictional providers and the three walks`() {
        assertEquals(Provider.DEMO_APPS, script.providerOrder)
        assertEquals(setOf("main", "keep_searching", "rematch"), script.tracks.map { it.id }.toSet())
        assertEquals(Provider.DEMO_APPS, script.track("main")!!.providers)
        assertEquals(listOf(Provider.DEMO_PEPPER), script.track("rematch")!!.providers)
        assertEquals(false, script.track("main")!!.requires.keepSearching)
        assertEquals(true, script.track("keep_searching")!!.requires.keepSearching)
        assertEquals(10.81, script.trip.tripKm, 1e-9)
        DemoTrackChoice.entries.forEach { assertNotNull(script.track(it.trackId)) }
    }

    @Test
    fun `every bundled screen exists and every post is read by its demo template exactly as the script expects`() {
        script.providers.values.flatMap { it.screens.values }.forEach { path ->
            val bytes = BundledAssets.bytes(path)
            assertEquals(1080 to 2400, DemoImages.pngSize(bytes))
        }
        for (track in script.tracks) {
            for (event in track.events.filter { it.action == DemoAction.POST }) {
                val snapshot = event.toSnapshot(1L, script.providers.getValue(event.packageName).versionCode)
                val entry = BundledAssets.templates.entryFor(event.packageName, snapshot.versionCode)!!.entry
                val hit = TemplateEngine.match(entry, snapshot, null)
                assertNotNull("${track.id}/${event.id} matches a template", hit)
                assertEquals("${track.id}/${event.id}", event.expectEvent, hit!!.event)
                event.expectGroups["eta"]?.let { assertEquals(it.toInt(), hit.etaMin) }
                event.expectGroups["dist"]?.let { assertEquals(TemplateEngine.parseDistanceMeters(it), hit.distanceMeters) }
                event.expectGroups["plate"]?.let { assertEquals(PlateHasher.hash(it), hit.plateHash) }
            }
        }
    }

    @Test
    fun `script events become RideWatch posts and removals`() {
        val event = script.track("main")!!.events.first { it.id == "pepper_assigned_4" }
        val snapshot = event.toSnapshot(42L, 1)
        assertEquals("demo.pepper", snapshot.packageName)
        assertEquals(7101, snapshot.id)
        assertEquals("ride", snapshot.tag)
        assertEquals("pepper_ride_status", snapshot.channelId)
        assertEquals(true, snapshot.isOngoing)
        assertEquals("Driver assigned\nRavi · XX 00 DM 4821 · arriving in 9 min · 2.8 km away", snapshot.matchText)
        assertEquals(42L, snapshot.postTimeMs)
        assertEquals(1L, snapshot.versionCode)

        val removal = script.track("main")!!.events.first { it.id == "kestrel_ongoing_removed_6" }.toRemoval()
        assertEquals(RemovalReason.REASON_APP_CANCEL, removal.reason)
        assertEquals("0|demo.kestrel|7001|ride|10501", removal.sbnKey)
    }

    @Test
    fun `user steps parse and round-trip`() {
        val wires = listOf("book:demo.kestrel", "cancel_others:yes", "cancel_others:not_now", "tapped_cancel:demo.tamarind", "better_ride:yes")
        wires.forEach { assertEquals(it, DemoUserStep.parse(it)!!.wire) }
        assertEquals(DemoUserStep.Book(Provider.DEMO_KESTREL), DemoUserStep.parse("book:demo.kestrel"))
        listOf("book:com.ubercab", "book:", "cancel_others:maybe", "better_ride:no", "dance:demo.pepper", "").forEach {
            assertNull(it, DemoUserStep.parse(it))
        }
        assertTrue(script.tracks.flatMap { it.events }.all { it.userStep != null })
    }

    @Test
    fun `sbn keys give the notification id and tag`() {
        assertEquals(7001 to "ride", DemoEvent.parseSbnKey("0|demo.kestrel|7001|ride|10501"))
        assertEquals(5 to null, DemoEvent.parseSbnKey("0|demo.kestrel|5|null|1"))
        assertEquals("odd".hashCode() to null, DemoEvent.parseSbnKey("odd"))
    }

    @Test
    fun `broken scripts are rejected at load`() {
        val raw = BundledAssets.text(DemoScript.ASSET_PATH)
        fun mutate(change: (JsonObject) -> JsonObject): String = change(Json.parseToJsonElement(raw) as JsonObject).toString()
        fun firstEvent(root: JsonObject, change: (JsonObject) -> JsonObject): JsonObject {
            val tracks = root["tracks"] as JsonArray
            val track = tracks[0] as JsonObject
            val events = (track["events"] as JsonArray).toMutableList()
            events[0] = change(events[0] as JsonObject)
            val newTrack = JsonObject(track + ("events" to JsonArray(events)))
            return JsonObject(root + ("tracks" to JsonArray(listOf(newTrack) + tracks.drop(1))))
        }
        val cases = mapOf(
            "schema 2" to mutate { JsonObject(it + ("schema" to JsonPrimitive("2.0"))) },
            "not json" to "{",
            "live package" to mutate { firstEvent(it) { e -> JsonObject(e + ("package" to JsonPrimitive("com.ubercab"))) } },
            "bad step" to mutate { firstEvent(it) { e -> JsonObject(e + ("requires_user_step" to JsonPrimitive("wave:demo.pepper"))) } },
            "unknown screen" to mutate { firstEvent(it) { e -> JsonObject(e + ("screen" to JsonPrimitive("lobby"))) } },
            "remove without reason" to mutate { firstEvent(it) { e -> JsonObject(e + ("action" to JsonPrimitive("remove"))) } },
            "negative time" to mutate { firstEvent(it) { e -> JsonObject(e + ("t_ms" to JsonPrimitive(-1))) } },
        )
        cases.forEach { (name, text) -> assertThrows(name, IllegalArgumentException::class.java) { DemoScript.parse(text) } }
    }

    @Test
    fun `track events for a step come in firing order`() {
        val events = script.track("main")!!.eventsFor(DemoUserStep.TappedCancel(Provider.DEMO_TAMARIND))
        assertEquals(events.map { it.tMs }.sorted(), events.map { it.tMs })
        assertEquals(NotificationEvent.CANCELLED, events.first().expectEvent)
    }
}
