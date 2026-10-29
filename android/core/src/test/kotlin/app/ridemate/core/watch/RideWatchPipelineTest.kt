@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.ridemate.core.watch

import app.ridemate.core.config.NotificationEvent
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.RideState
import app.ridemate.core.replay.FakeClassifier
import app.ridemate.core.replay.Fixtures
import app.ridemate.core.replay.Fixtures.Text
import app.ridemate.core.replay.SchedulerClock
import app.ridemate.core.state.DriverData
import app.ridemate.core.state.NotificationRemoved
import app.ridemate.core.state.ProviderUpdate
import app.ridemate.core.state.RideEvent
import app.ridemate.core.state.SignalSource
import app.ridemate.core.state.Statechart
import app.ridemate.core.state.UnknownReason
import app.ridemate.core.state.UnknownUpdate
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideWatchPipelineTest {

    /** A leg table and event recorder standing in for the guardian. */
    private class FakeLegs : LegDirectory, RideEventSink {
        var session: String? = "s1"
        val legs = mutableMapOf<String, LegView>()
        val events = mutableListOf<Pair<String, RideEvent>>()
        override val activeSessionId: String? get() = session
        override fun legFor(packageName: String): LegView? = legs[packageName]
        override fun onRideEvent(legId: String, event: RideEvent): Disposition {
            events += legId to event
            return Disposition.TRANSITION
        }
    }

    private class Rig(test: TestScope, val classifier: FakeClassifier? = FakeClassifier(), masker: TextMasker = TextMasker.NONE) {
        val clock = SchedulerClock(test.testScheduler)
        val legs = FakeLegs()
        val pipeline = RideWatchPipeline(clock, test.backgroundScope, legs, legs, { Fixtures.templates }, classifier, masker)
        private val scheduler = test.testScheduler

        fun leg(pkg: String, state: RideState = RideState.SEARCHING, booked: Boolean = true) {
            legs.legs[pkg] = LegView("leg:$pkg", state, 0, booked)
        }

        fun post(pkg: String, key: String, title: String?, text: String?, id: Int = 1, ongoing: Boolean = true, channel: String? = Fixtures.rideChannel(pkg)): PostAuditEntry {
            pipeline.onPosted(PostSource.of(PostSnapshot(pkg, key, id, null, channel, null, ongoing, title, text, clock.wallMs(), 100)))
            scheduler.runCurrent()
            return pipeline.audit.last()
        }

        fun post(pkg: String, key: String, content: Pair<String, String>) = post(pkg, key, content.first, content.second)

        fun advance(ms: Long) {
            scheduler.advanceTimeBy(ms)
            scheduler.runCurrent()
        }
    }

    @Test
    fun `I7 - other packages are dropped before any other field is read`() = runTest {
        val rig = Rig(this)
        var reads = 0
        val foreign = object : PostSource {
            override val packageName = "com.whatsapp"
            override fun read(): PostSnapshot {
                reads++
                error("must not be read")
            }
        }
        rig.pipeline.onPosted(foreign)
        rig.pipeline.onRemoved(object : RemovalSource {
            override val packageName = "com.example.bank"
            override fun read(): PostRemoval = error("must not be read")
        })
        rig.pipeline.onListenerConnected(listOf(foreign))
        assertEquals(0, reads)
        assertEquals(3, rig.pipeline.nonAllowlistedDropped)
        assertTrue(rig.pipeline.audit.isEmpty())
        assertTrue(rig.legs.events.isEmpty())
    }

    @Test
    fun `demo packages are read only while a demo session is explicitly active`() = runTest {
        val rig = Rig(this)
        rig.leg("demo.kestrel")
        rig.pipeline.onPosted(PostSource.of(PostSnapshot("demo.kestrel", "d1", 1, null, null, null, true, "x", "y", 0, 1)))
        assertEquals(1, rig.pipeline.nonAllowlistedDropped)
        rig.pipeline.demoSessionActive = true
        rig.pipeline.onPosted(PostSource.of(PostSnapshot("demo.kestrel", "d1", 1, null, null, null, true, "x", "y", 0, 1)))
        assertEquals(1, rig.pipeline.audit.size)
    }

    @Test
    fun `a template hit becomes a deterministic event immediately`() = runTest {
        val rig = Rig(this)
        rig.leg(Fixtures.RAPIDO)
        val entry = rig.post(Fixtures.RAPIDO, "r1", Text.rapidoAssigned(eta = 4))
        assertEquals(PostOutcome.EVENT, entry.outcome)
        assertEquals(entry.arrivedAtElapsedMs, entry.resolvedAtElapsedMs)
        val event = rig.legs.events.single().second as ProviderUpdate
        assertEquals(NotificationEvent.ASSIGNED, event.signal)
        assertEquals(SignalSource.TEMPLATE, event.source)
        assertEquals("r1", event.sbnKey)
        assertEquals(4, event.etaMin)
        assertEquals(PlateHasher.hash("AS01AB1234"), event.plateHash)
        assertTrue(rig.classifier!!.requests.isEmpty())
    }

    @Test
    fun `only exact repeats of the last processed post on a key are dropped`() = runTest {
        val rig = Rig(this)
        rig.leg(Fixtures.RAPIDO)
        rig.post(Fixtures.RAPIDO, "r1", "Looking for a captain", "elapsed 00:02")
        val dup = rig.post(Fixtures.RAPIDO, "r1", "Looking for a captain", "elapsed 00:04")
        assertEquals(PostOutcome.DUPLICATE_DROPPED, dup.outcome)
        // Same wording on another key is not a repeat.
        assertEquals(PostOutcome.EVENT, rig.post(Fixtures.RAPIDO, "r2", "Looking for a captain", "elapsed 00:04").outcome)
        // A different post in between makes the old wording new again.
        rig.post(Fixtures.RAPIDO, "r1", Text.rapidoAssigned())
        assertEquals(PostOutcome.EVENT, rig.post(Fixtures.RAPIDO, "r1", "Looking for a captain", "elapsed 00:09").outcome)
        assertEquals(4, rig.legs.events.size)
    }

    @Test
    fun `dedupe is per session - the same post in the next session is an event again`() = runTest {
        val rig = Rig(this)
        rig.leg(Fixtures.RAPIDO)
        assertEquals(PostOutcome.EVENT, rig.post(Fixtures.RAPIDO, "r1", "Looking for a captain", "elapsed 00:02").outcome)
        assertEquals(PostOutcome.DUPLICATE_DROPPED, rig.post(Fixtures.RAPIDO, "r1", "Looking for a captain", "elapsed 00:03").outcome)
        // Try the demo twice in a row posts the same words on the same keys: the second run must see them.
        rig.pipeline.onSessionStarted()
        assertEquals(PostOutcome.EVENT, rig.post(Fixtures.RAPIDO, "r1", "Looking for a captain", "elapsed 00:04").outcome)
    }

    @Test
    fun `a new plate with the same masked wording is not a repeat, a new countdown only refreshes numbers`() = runTest {
        val plateSpans = TextMasker { text -> Regex("[A-Z]{2}\\d{2}[A-Z]{2}\\d{4}").findAll(text).map { it.range }.toList() }
        val rig = Rig(this, masker = plateSpans)
        rig.leg(Fixtures.RAPIDO, RideState.ASSIGNED)
        rig.post(Fixtures.RAPIDO, "r1", Text.rapidoAssigned(eta = 4, plate = "AS01AB1234"))
        val countdown = rig.post(Fixtures.RAPIDO, "r1", Text.rapidoAssigned(eta = 3, plate = "AS01AB1234"))
        assertEquals(PostOutcome.DUPLICATE_DROPPED, countdown.outcome)
        val refresh = rig.legs.events.last().second as DriverData
        assertEquals(3, refresh.etaMin)
        assertEquals(EtaSource.TEMPLATE, refresh.source)
        val newDriver = rig.post(Fixtures.RAPIDO, "r1", Text.rapidoAssigned(eta = 3, plate = "AS09ZZ9999"))
        assertEquals(PostOutcome.EVENT, newDriver.outcome)
        assertEquals(PlateHasher.hash("AS09ZZ9999"), (rig.legs.events.last().second as ProviderUpdate).plateHash)
    }

    @Test
    fun `a redacted post on an active leg is an UNKNOWN_UPDATE, told apart structurally`() = runTest {
        val rig = Rig(this)
        rig.leg(Fixtures.UBER)
        val first = rig.post(Fixtures.UBER, "u1", null, null, id = 5)
        assertEquals(PostKind.REDACTED, first.kind)
        assertEquals(UnknownReason.REDACTED, (rig.legs.events.single().second as UnknownUpdate).reason)
        assertEquals(PostOutcome.DUPLICATE_DROPPED, rig.post(Fixtures.UBER, "u1", null, null, id = 5).outcome)
        assertEquals(PostOutcome.EVENT, rig.post(Fixtures.UBER, "u1", null, null, id = 6).outcome)
        rig.advance(10_000)
        assertEquals("next 10 s bucket", PostOutcome.EVENT, rig.post(Fixtures.UBER, "u1", null, null, id = 6).outcome)
    }

    @Test
    fun `rule misses outside a session or on an unbooked leg are never classified`() = runTest {
        val rig = Rig(this)
        val noLeg = rig.post(Fixtures.OLA, "o1", "Ola", "Something new happened")
        assertEquals(PostOutcome.NO_ACTIVE_LEG, noLeg.outcome)
        rig.leg(Fixtures.OLA, RideState.QUOTED, booked = false)
        rig.post(Fixtures.OLA, "o1", "Ola", "Something else happened")
        assertEquals(UnknownReason.NOT_CLASSIFIED, (rig.legs.events.single().second as UnknownUpdate).reason)
        rig.legs.session = null
        rig.leg(Fixtures.OLA)
        rig.post(Fixtures.OLA, "o1", "Ola", "A third thing")
        assertTrue(rig.classifier!!.requests.isEmpty())
    }

    @Test
    fun `a rule miss is classified with masked text and the label becomes a proposal`() = runTest {
        val phone = TextMasker { text -> Regex("\\d{10}").findAll(text).map { it.range }.toList() }
        val rig = Rig(this, masker = phone)
        rig.classifier!!.rules += "accepted" to ClassifierLabel.ASSIGNED
        rig.leg(Fixtures.RAPIDO)
        val miss = rig.post(Fixtures.RAPIDO, "r1", "Rapido", "Captain accepted! Call 9876543210")
        assertEquals(PostOutcome.PENDING_CLASSIFICATION, miss.outcome)
        val request = rig.classifier.requests.single()
        assertEquals("rapido", request.app)
        assertEquals("Captain accepted! Call <m>", request.text)
        assertEquals(RideState.SEARCHING, request.legState)
        rig.advance(1_500)
        assertEquals(PostOutcome.EVENT, miss.outcome)
        val event = rig.legs.events.single().second as ProviderUpdate
        assertEquals(SignalSource.CLASSIFIER, event.source)
        assertEquals(NotificationEvent.ASSIGNED, event.signal)
        assertNull(event.etaMin)
    }

    @Test
    fun `labels other than searching or assigned only raise a prompt`() = runTest {
        val rig = Rig(this)
        rig.classifier!!.rules += "gone" to ClassifierLabel.CANCELLED
        rig.leg(Fixtures.RAPIDO, RideState.SEARCHING)
        rig.post(Fixtures.RAPIDO, "r1", "Rapido", "Your captain is gone")
        rig.advance(1_500)
        assertEquals(UnknownReason.CLASSIFIER_LABEL, (rig.legs.events.single().second as UnknownUpdate).reason)
    }

    @Test
    fun `no classification is spent where no label could change the outcome`() = runTest {
        for (state in RideState.entries.filter { it !in Statechart.CLASSIFIABLE && !it.isTerminal }) {
            val rig = Rig(this)
            rig.classifier!!.rules += "update" to ClassifierLabel.ASSIGNED
            rig.leg(Fixtures.RAPIDO, state)
            val miss = rig.post(Fixtures.RAPIDO, "r1", "Rapido", "An update from your captain")
            assertEquals(state.name, PostOutcome.EVENT, miss.outcome)
            assertEquals(state.name, miss.arrivedAtElapsedMs, miss.resolvedAtElapsedMs)
            assertTrue(state.name, rig.classifier.requests.isEmpty())
            assertEquals(state.name, UnknownReason.LABEL_CANNOT_APPLY, (rig.legs.events.single().second as UnknownUpdate).reason)
        }
        for (state in Statechart.CLASSIFIABLE) {
            val rig = Rig(this)
            rig.leg(Fixtures.RAPIDO, state)
            rig.post(Fixtures.RAPIDO, "r1", "Rapido", "An update from your captain")
            assertEquals(state.name, 1, rig.classifier!!.requests.size)
        }
        // A post waiting behind a classification is not sent once its leg has moved somewhere no label matters.
        val rig = Rig(this)
        rig.classifier!!.latencyMs = 2_000
        rig.leg(Fixtures.OLA)
        rig.post(Fixtures.OLA, "o1", "Ola", "update alpha")
        rig.advance(500)
        val waiting = rig.post(Fixtures.OLA, "o1", "Ola", "update beta")
        rig.leg(Fixtures.OLA, RideState.ASSIGNED)
        rig.advance(1_500)
        assertEquals(1, rig.classifier.requests.size)
        assertEquals(PostOutcome.EVENT, waiting.outcome)
        assertEquals(UnknownReason.LABEL_CANNOT_APPLY, waiting.unknownReason)
    }

    @Test
    fun `the per-session cache answers a repeated wording without a second call`() = runTest {
        val rig = Rig(this)
        rig.classifier!!.rules += "matched" to ClassifierLabel.SEARCHING
        rig.leg(Fixtures.UBER)
        rig.post(Fixtures.UBER, "u1", "Uber", "We matched your request 1")
        rig.advance(2_000)
        rig.post(Fixtures.UBER, "u1", Text.uberSearching)
        val again = rig.post(Fixtures.UBER, "u1", "Uber", "We matched your request 2")
        assertEquals(PostOutcome.EVENT, again.outcome)
        assertFalse(again.classifierCalled)
        assertEquals(1, rig.classifier.requests.size)
        // A new session starts with an empty cache.
        rig.legs.session = "s2"
        rig.post(Fixtures.UBER, "u1", Text.uberSearching)
        rig.post(Fixtures.UBER, "u1", "Uber", "We matched your request 3")
        assertEquals(2, rig.classifier.requests.size)
    }

    @Test
    fun `at most one classification in flight per key and only the latest waiting post is classified`() = runTest {
        val rig = Rig(this)
        rig.classifier!!.latencyMs = 2_000
        rig.leg(Fixtures.OLA)
        val a = rig.post(Fixtures.OLA, "o1", "Ola", "update alpha")
        rig.advance(500)
        val b = rig.post(Fixtures.OLA, "o1", "Ola", "update beta")
        rig.advance(500)
        val c = rig.post(Fixtures.OLA, "o1", "Ola", "update gamma")
        assertEquals(1, rig.classifier.requests.size)
        assertEquals(PostOutcome.COALESCED, b.outcome)
        rig.advance(1_000)
        assertEquals(PostOutcome.EVENT, a.outcome)
        assertEquals(listOf("update alpha", "update gamma"), rig.classifier.requests.map { it.text })
        rig.advance(2_000)
        assertEquals(PostOutcome.EVENT, c.outcome)
        assertEquals(PostOutcome.COALESCED, b.outcome)
        assertEquals(c.seq, b.coveredBySeq)
        assertTrue("b resolved within its own bound", b.resolvedAtElapsedMs!! - b.arrivedAtElapsedMs <= 13_000)
        assertEquals(2, rig.legs.events.size)
    }

    @Test
    fun `a template hit on the same key supersedes a classification in flight`() = runTest {
        val rig = Rig(this)
        rig.classifier!!.latencyMs = 3_000
        rig.classifier.rules += "update" to ClassifierLabel.SEARCHING
        rig.leg(Fixtures.OLA)
        val miss = rig.post(Fixtures.OLA, "o1", "Ola", "update alpha")
        rig.advance(1_000)
        val hit = rig.post(Fixtures.OLA, "o1", Text.olaAssigned())
        assertEquals(PostOutcome.SUPERSEDED, miss.outcome)
        assertEquals(hit.seq, miss.coveredBySeq)
        rig.advance(3_000)
        assertEquals("the stale label is not applied", 1, rig.legs.events.size)
    }

    @Test
    fun `classifier down, anonymous, or past the 10 s deadline falls back to UNKNOWN_UPDATE`() = runTest {
        for ((mode, reason, at) in listOf(
            Triple(FakeClassifier.Mode.DOWN, UnknownReason.CLASSIFIER_FAILED, 0L),
            Triple(FakeClassifier.Mode.ANONYMOUS, UnknownReason.CLASSIFIER_UNAVAILABLE, 0L),
            Triple(FakeClassifier.Mode.HANG, UnknownReason.CLASSIFIER_TIMEOUT, 10_000L),
        )) {
            val rig = Rig(this, FakeClassifier(mode))
            rig.leg(Fixtures.UBER)
            val miss = rig.post(Fixtures.UBER, "u1", "Uber", "Something new")
            rig.advance(at)
            assertEquals(mode.name, PostOutcome.EVENT, miss.outcome)
            assertEquals(mode.name, reason, (rig.legs.events.single().second as UnknownUpdate).reason)
            assertEquals(mode.name, at, miss.resolvedAtElapsedMs!! - miss.arrivedAtElapsedMs)
        }
        val none = Rig(this, classifier = null)
        none.leg(Fixtures.UBER)
        none.post(Fixtures.UBER, "u1", "Uber", "Something new")
        assertEquals(UnknownReason.CLASSIFIER_UNAVAILABLE, (none.legs.events.single().second as UnknownUpdate).reason)
    }

    @Test
    fun `reconnect re-reads every active post as new, and tracks ongoing ride posts`() = runTest {
        val rig = Rig(this)
        rig.leg(Fixtures.RAPIDO)
        val searching = PostSnapshot(Fixtures.RAPIDO, "r1", 1, null, "ride_status", null, true, Text.rapidoSearching.first, Text.rapidoSearching.second, rig.clock.wallMs(), 100)
        rig.pipeline.onPosted(PostSource.of(searching))
        assertTrue(rig.pipeline.hasOngoingRide(Fixtures.RAPIDO))
        rig.pipeline.onListenerDisconnected()
        assertFalse(rig.pipeline.listenerConnected)
        rig.pipeline.onListenerConnected(listOf(PostSource.of(searching)))
        assertTrue(rig.pipeline.listenerConnected)
        val replay = rig.pipeline.audit.last()
        assertTrue(replay.replayed)
        assertEquals(PostOutcome.EVENT, replay.outcome)
        assertTrue((rig.legs.events.last().second as ProviderUpdate).replayed)
        assertEquals(2, rig.legs.events.size)
    }

    @Test
    fun `removals are recorded with their reason and whether the post was an ongoing ride post`() = runTest {
        val rig = Rig(this)
        rig.leg(Fixtures.UBER)
        rig.post(Fixtures.UBER, "u1", Text.uberSearching)
        rig.post(Fixtures.UBER, "u2", "Uber", "Rate your last trip", ongoing = false, channel = "promo")
        rig.pipeline.onRemoved(RemovalSource.of(PostRemoval(Fixtures.UBER, "u2", RemovalReason.REASON_CANCEL)))
        assertTrue(rig.pipeline.hasOngoingRide(Fixtures.UBER))
        rig.pipeline.onRemoved(RemovalSource.of(PostRemoval(Fixtures.UBER, "u1", RemovalReason.REASON_APP_CANCEL)))
        assertFalse(rig.pipeline.hasOngoingRide(Fixtures.UBER))
        assertEquals(listOf(2, 8), rig.pipeline.removals.map { it.reasonCode })
        assertEquals(listOf(false, true), rig.pipeline.removals.map { it.wasOngoing })
        val removal = rig.legs.events.last().second as NotificationRemoved
        assertTrue(removal.appCancel && removal.wasOngoing)
        // A removed post that comes back is processed as new.
        assertEquals(PostOutcome.EVENT, rig.post(Fixtures.UBER, "u1", Text.uberSearching).outcome)
    }

    @Test
    fun `the post and removal logs keep only the most recent entries`() = runTest {
        val clock = SchedulerClock(testScheduler)
        val legs = FakeLegs().apply { this.legs[Fixtures.UBER] = LegView("leg", RideState.SEARCHING, 0, true) }
        val pipeline = RideWatchPipeline(clock, backgroundScope, legs, legs, { Fixtures.templates }, maxLogEntries = 3)
        repeat(5) { i ->
            val post = PostSnapshot(Fixtures.UBER, "u$i", i, null, "trip", null, true, "Finding your ride", "We're looking $i", clock.wallMs(), 100)
            pipeline.onPosted(PostSource.of(post))
            pipeline.onRemoved(RemovalSource.of(PostRemoval(Fixtures.UBER, "u$i", RemovalReason.REASON_APP_CANCEL)))
        }
        assertEquals(listOf(3L, 4L, 5L), pipeline.audit.map { it.seq })
        assertEquals(listOf("u2", "u3", "u4"), pipeline.removals.map { it.sbnKey })
        assertEquals("every post still reached the leg", 10, legs.events.size)
    }

    @Test
    fun `an unknown app version uses the newest template and is flagged`() = runTest {
        val rig = Rig(this)
        rig.leg(Fixtures.OLA)
        rig.pipeline.onPosted(PostSource.of(PostSnapshot(Fixtures.OLA, "o1", 1, null, null, null, true, "Searching for a cab", "", 0, null)))
        assertTrue(rig.pipeline.audit.last().templateFallback)
        assertEquals("ola.searching.v1", rig.pipeline.audit.last().templateId)
    }
}
