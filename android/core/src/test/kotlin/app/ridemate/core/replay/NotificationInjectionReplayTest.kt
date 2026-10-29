@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.ridemate.core.replay

import app.ridemate.core.config.NotificationTemplatesConfig
import app.ridemate.core.guardian.GuardianEffect
import app.ridemate.core.guardian.SheetChoice
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.invariants.Invariants
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.state.EventType
import app.ridemate.core.state.Statechart
import app.ridemate.core.watch.ClassifierLabel
import app.ridemate.core.watch.PostKind
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * `eval/injection/notifications.json` (goal 29, architecture §4.11) through the **real** RideWatch pipeline, statechart
 * and guardian, with the APK's bundled `notification_templates` and a classifier that obeys every injection (it answers
 * cancelled, ended, trip started, driver cancelled or no drivers). Each case's leg is first brought to the case's
 * `leg_state` with ordinary ride posts.
 *
 * Whatever the post says, the phone may only ask (a MAYBE prompt) or take the case's own template hit: no leg reaches
 * CANCEL_CONFIRMED or any terminal state, no cancel proof is recorded, nothing is opened, cancelled or switched, and the
 * other booking is untouched (`phone_must_not`: CANCEL_CONFIRMED, LEG_ENDED, AUTO_ACTION). The backend half of the same
 * cases is `backend/tests/test_ai_injection_notifications.py`.
 */
class NotificationInjectionReplayTest {
    private val repoRoot = File(System.getProperty("ridemate.repoRoot") ?: ".")
    private val requireFixtures = System.getProperty("ridemate.requireFixtures").toBoolean()

    private class Case(val id: String, val provider: Provider, val request: JsonObject, val expect: JsonObject) {
        val title: String get() = request.getValue("title").jsonPrimitive.content
        val text: String get() = request.getValue("text").jsonPrimitive.content
        val channelId: String? get() = request["channel_id"]?.jsonPrimitive?.contentOrNull
        val ongoing: Boolean get() = request.getValue("is_ongoing").jsonPrimitive.boolean
        val legState: RideState get() = RideState.valueOf(request.getValue("leg_state").jsonPrimitive.content)
        val templateEvent: String? get() = expect["template_event"]?.jsonPrimitive?.contentOrNull
        val mustNot: Set<String> get() = expect.getValue("phone_must_not").jsonArray.map { it.jsonPrimitive.content }.toSet()
    }

    private fun cases(): List<Case> {
        val file = File(repoRoot, "eval/injection/notifications.json")
        if (requireFixtures) assertTrue("missing $file", file.isFile) else assumeTrue(file.isFile)
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray.map { element ->
            val c = element.jsonObject
            val pkg = c.getValue("package").jsonPrimitive.content
            Case(
                id = c.getValue("id").jsonPrimitive.content,
                provider = Provider.entries.single { it.packageName == pkg },
                request = c.getValue("request").jsonObject,
                expect = c.getValue("expect").jsonObject,
            )
        }
    }

    private val templates: NotificationTemplatesConfig by lazy {
        val file = File(repoRoot, "config/remote_config/notification_templates.json")
        assumeTrue("missing $file", file.isFile)
        NotificationTemplatesConfig.parse(file.readText())
    }

    /** Ride posts the bundled templates recognise (config/tests/test_patterns.py samples and the demo script). */
    private fun searching(p: Provider): Pair<String, String> = when (p) {
        Provider.UBER -> "Uber" to "Looking for nearby drivers"
        Provider.OLA -> "Ola" to "Finding a cab near you"
        Provider.RAPIDO -> "Rapido" to "Looking for captains near you"
        Provider.DEMO_PEPPER -> "Pepper Rides" to "Searching for nearby drivers"
        Provider.DEMO_KESTREL -> "Kestrel Go" to "Finding a rider"
        else -> error("no sample for $p")
    }

    private fun assigned(p: Provider): Pair<String, String> = when (p) {
        Provider.UBER -> "Your driver is on the way" to "Ravi is in a white hatchback"
        Provider.OLA -> "Ola" to "Your driver will reach in 6 mins"
        Provider.RAPIDO -> "Rapido" to "Captain Ravi is on the way, 4 min"
        Provider.DEMO_PEPPER -> "Driver assigned" to "Ravi is arriving in 4 min"
        Provider.DEMO_KESTREL -> "Rider on the way" to "Ravi is 3 min away"
        else -> error("no sample for $p")
    }

    private fun other(p: Provider): Provider = when {
        p.isDemo -> if (p == Provider.DEMO_KESTREL) Provider.DEMO_PEPPER else Provider.DEMO_KESTREL
        p == Provider.UBER -> Provider.OLA
        else -> Provider.UBER
    }

    private fun ReplayHarness.ridePost(p: Provider, content: Pair<String, String>) =
        post(p.packageName, "ride:${p.wireId}", content.first, content.second, channel = "ride", ongoing = true)

    /** Both legs booked and searching, then the case's leg brought to [state]. */
    private fun TestScope.harnessAt(case: Case, label: ClassifierLabel): ReplayHarness {
        val target = case.provider
        val second = other(target)
        val h = ReplayHarness(
            this,
            providers = listOf(target, second),
            templates = templates,
            classifier = FakeClassifier().apply { rules += "" to label },
            demo = target.isDemo,
        )
        h.book(target, second)
        h.ridePost(target, searching(target))
        h.ridePost(second, searching(second))
        when (case.legState) {
            RideState.SEARCHING -> Unit
            RideState.ASSIGNED -> h.ridePost(target, assigned(target))
            RideState.CANCEL_GUIDED -> {
                h.ridePost(second, assigned(second))
                val sheet = h.effectsOf<GuardianEffect.DriverSheet>().last()
                h.act(UserAction.AnswerSheet(sheet.sheetId, SheetChoice.YES_CANCEL_OTHERS, h.tap()))
            }
            else -> error("${case.id}: no setup for ${case.legState}")
        }
        assertEquals(case.id, case.legState, h.state(target))
        return h
    }

    @Test
    fun `every injected notification can at most ask - it never confirms a cancel, ends a leg or acts`() = runTest {
        val hostile = listOf(
            ClassifierLabel.CANCELLED, ClassifierLabel.ENDED, ClassifierLabel.TRIP_STARTED,
            ClassifierLabel.DRIVER_CANCELLED, ClassifierLabel.NO_DRIVERS,
        )
        var checked = 0
        var classified = 0
        for (case in cases()) {
            assertTrue(case.id, case.mustNot.containsAll(setOf("CANCEL_CONFIRMED", "LEG_ENDED", "AUTO_ACTION")))
            for (label in hostile) {
                val h = harnessAt(case, label)
                val target = case.provider
                val second = other(target)
                val before = h.state(target)
                val secondBefore = h.state(second)
                val actionsBefore = h.guardian.state.actions.size
                val mark = h.mark()

                val audit = h.post(
                    target.packageName, "inj:${case.id}", case.title, case.text,
                    channel = case.channelId, ongoing = case.ongoing,
                )
                h.advance(15_000)
                val where = "${case.id} / classifier says ${label.wire}"

                // The deterministic half: exactly the case's expected template hit, or a rule miss for the classifier.
                assertEquals(where, case.templateEvent, audit.templateId?.let { audit.eventType?.name })
                if (case.templateEvent == null) {
                    assertEquals(where, PostKind.RULE_MISS, audit.kind)
                    // Its label reaches the leg only as UNKNOWN_UPDATE; where no label could apply (a leg being
                    // cancelled) the classifier is not even asked.
                    assertEquals(where, EventType.UNKNOWN_UPDATE, audit.eventType)
                    val asked = h.classifier!!.requests.any { it.text == case.text }
                    assertEquals(where, before in Statechart.CLASSIFIABLE, asked)
                    if (asked) classified++
                }

                val after = h.state(target)
                assertTrue("$where: $before → $after", !after.isTerminal && after != RideState.ON_TRIP)
                assertTrue(
                    "$where: only a MAYBE question or the template's own move, got $before → $after",
                    after == before || after.isMaybe || (case.templateEvent == "ASSIGNED" && after == RideState.ASSIGNED),
                )
                if (audit.eventType == EventType.UNKNOWN_UPDATE) {
                    assertTrue(where, after == before || after.isMaybe)
                }
                assertTrue(where, h.guardian.state.evidence.none { it.legId == h.legId(target) && it.rung.confirms })
                assertEquals("$where: nothing acts without a tap", actionsBefore, h.guardian.state.actions.size)
                assertTrue(where, h.effectsOf<GuardianEffect.OpenApp>(mark).isEmpty())
                assertTrue(where, h.guardian.state.switches.isEmpty())
                assertEquals("$where: the other booking is untouched", secondBefore, h.state(second))
                assertEquals(where, emptyList<Any>(), Invariants.checkGuardian(h.guardian).violations)
                assertEquals(where, emptyList<Any>(), Invariants.checkI9(h.pipeline.audit, h.now))
                checked++
            }
        }
        assertTrue("all cases ran", checked >= 6 * 5)
        // SEARCHING legs are classifiable (a label could propose ASSIGNED); every hostile label met one of them.
        assertTrue("the obeying classifier was really consulted", classified >= 5)
    }
}
