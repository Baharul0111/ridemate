@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.ridemate.demo

import app.ridemate.core.model.Provider
import app.ridemate.core.model.Provider.DEMO_KESTREL
import app.ridemate.core.model.Provider.DEMO_PEPPER
import app.ridemate.core.model.Provider.DEMO_TAMARIND
import app.ridemate.core.watch.PostRemoval
import app.ridemate.core.watch.PostSnapshot
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoEventSourceTest {
    private val script = BundledAssets.script

    private class RecordingSink(private val test: TestScope) : DemoEventSink {
        val log = mutableListOf<Triple<Long, String, String>>()
        val screens = mutableListOf<Pair<Provider, String>>()
        override fun post(event: DemoEvent, snapshot: PostSnapshot) {
            log += Triple(test.testScheduler.currentTime, "post", event.id)
        }
        override fun remove(event: DemoEvent, removal: PostRemoval) {
            log += Triple(test.testScheduler.currentTime, "remove", event.id)
        }
        override fun screenChanged(provider: Provider, screen: String) {
            screens += provider to screen
        }
    }

    private fun TestScope.source(track: String, sink: RecordingSink) =
        DemoEventSource(script, script.track(track)!!, backgroundScope, { 0L }, sink)

    @Test
    fun `events fire t_ms after their step, in script order, once`() = runTest {
        val sink = RecordingSink(this)
        val source = source("main", sink)
        assertTrue(source.onUserStep(DemoUserStep.Book(DEMO_KESTREL)))
        testScheduler.advanceTimeBy(1_199)
        testScheduler.runCurrent()
        assertTrue(sink.log.isEmpty())
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertEquals(listOf(Triple(1_200L, "post", "kestrel_searching_1")), sink.log)
        assertEquals(listOf(DEMO_KESTREL to DemoScreens.SEARCHING), sink.screens)

        assertFalse("a repeated step anchors nothing", source.onUserStep(DemoUserStep.Book(DEMO_KESTREL)))
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
        assertEquals(1, sink.log.size)

        source.onUserStep(DemoUserStep.TappedCancel(DEMO_KESTREL))
        val at = testScheduler.currentTime
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertEquals(
            listOf(Triple(at + 1_500, "post", "kestrel_cancelled_5"), Triple(at + 1_600, "remove", "kestrel_ongoing_removed_6")),
            sink.log.drop(1),
        )
        assertEquals(listOf("kestrel_searching_1", "kestrel_cancelled_5", "kestrel_ongoing_removed_6"), source.firedEventIds)
    }

    @Test
    fun `events anchored on the same step keep their order across providers`() = runTest {
        val sink = RecordingSink(this)
        val source = source("main", sink)
        source.onUserStep(DemoUserStep.Book(DEMO_TAMARIND))
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        assertEquals(listOf(1_200L to "tamarind_searching_3", 9_000L to "pepper_assigned_4"), sink.log.map { it.first to it.third })
    }

    @Test
    fun `cancel stops pending events`() = runTest {
        val sink = RecordingSink(this)
        val source = source("main", sink)
        source.onUserStep(DemoUserStep.Book(DEMO_TAMARIND))
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        source.cancel()
        testScheduler.advanceTimeBy(20_000)
        testScheduler.runCurrent()
        assertEquals(listOf("tamarind_searching_3"), sink.log.map { it.third })
    }

    @Test
    fun `a step the track does not script branches to a track that agrees with what was played`() = runTest {
        val sink = RecordingSink(this)
        val source = source("keep_searching", sink)
        listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND).forEach { source.onUserStep(DemoUserStep.Book(it)) }
        source.onUserStep(DemoUserStep.CancelOthersYes)
        assertEquals("keep_searching", source.track.id)
        source.onUserStep(DemoUserStep.TappedCancel(DEMO_KESTREL))
        assertEquals("keep_searching", source.track.id)
        source.onUserStep(DemoUserStep.TappedCancel(DEMO_TAMARIND))
        assertEquals("main", source.track.id)
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertTrue("tamarind's proof from the main track", "tamarind_cancelled_7" in source.firedEventIds)
        assertTrue("pepper's ride ends", "pepper_ended_11" in source.firedEventIds)
        assertFalse("nothing from the keep-searching branch that was not taken", "tamarind_assigned_5" in source.firedEventIds)
    }

    @Test
    fun `a branch never contradicts posts already played`() = runTest {
        val sink = RecordingSink(this)
        val source = source("main", sink)
        listOf(DEMO_KESTREL, DEMO_PEPPER, DEMO_TAMARIND).forEach { source.onUserStep(DemoUserStep.Book(it)) }
        source.onUserStep(DemoUserStep.CancelOthersNotNow)
        assertEquals("keep_searching", source.track.id)
        // Tamarind's driver was announced on this branch; the user then picks Pepper and cancels Tamarind:
        source.onUserStep(DemoUserStep.TappedCancel(DEMO_TAMARIND))
        assertEquals("main", source.track.id)
        // The rematch track books only Pepper, so it is never a candidate here.
        assertTrue(source.stepsTaken.contains("tapped_cancel:demo.tamarind"))
    }
}
