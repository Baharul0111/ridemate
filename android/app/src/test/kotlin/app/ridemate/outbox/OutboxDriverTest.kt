package app.ridemate.outbox

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.crash.CrashReporter
import app.ridemate.data.OutboxKind
import app.ridemate.data.OutboxStatus
import app.ridemate.data.RideMateDb
import app.ridemate.data.StorageHealth
import app.ridemate.testing.FakeBootIds
import app.ridemate.testing.FakeClock
import app.ridemate.testing.FakeCrashBackend
import app.ridemate.testing.TestData
import app.ridemate.testing.TestSeams
import app.ridemate.util.SessionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class OutboxDriverTest {
    private lateinit var db: RideMateDb
    private lateinit var repo: OutboxRepository
    private val clock = FakeClock(elapsed = 100_000L)
    private val session = SessionActivity()
    private val crashBackend = FakeCrashBackend()
    private lateinit var driver: OutboxDriver
    private val delivered = CopyOnWriteArrayList<OutboxItem>()

    @Before
    fun setUp() = runBlocking {
        db = TestSeams.inMemoryDb(ApplicationProvider.getApplicationContext())
        db.sessionDao().insert(TestData.session())
        db.legDao().upsert(TestData.leg("l1"))
        repo = OutboxRepository(db.outboxDao(), clock, FakeBootIds(), StorageHealth())
        driver = OutboxDriver(repo, clock, session, CrashReporter { crashBackend }, maxAttempts = 3)
    }

    @After
    fun tearDown() = db.close()

    private fun recordingHandler(outcome: (OutboxItem) -> OutboxOutcome = { OutboxOutcome.Done }) = OutboxHandler { item ->
        delivered += item
        outcome(item)
    }

    @Test
    fun `tick delivers only due items to the handler of their kind`() = runBlocking {
        driver.register(setOf(OutboxKind.REMINDER), recordingHandler())
        val due = repo.schedule(OutboxKind.REMINDER, "l1", 0)
        repo.schedule(OutboxKind.REMINDER, "l1", 30_000)

        val report = driver.tick()

        assertEquals(1, report.delivered)
        assertEquals(listOf(due.id), delivered.map { it.id })
        assertEquals(OutboxStatus.DONE, db.outboxDao().get(due.id)!!.status)
        assertEquals(clock.elapsed + 30_000, report.nextDueElapsedMs)

        clock.advance(30_000)
        assertEquals(1, driver.tick().delivered)
        assertEquals(TickReport(), driver.tick())
    }

    @Test
    fun `retry, dead and exceptions are handled`() = runBlocking {
        driver.register(setOf(OutboxKind.FOLLOWUP_CHECK), recordingHandler { OutboxOutcome.Retry(60_000) })
        driver.register(setOf(OutboxKind.OPEN_NEXT_APP), recordingHandler { OutboxOutcome.Dead })
        driver.register(setOf(OutboxKind.REMINDER), OutboxHandler { throw IllegalStateException("captain Ravi 98765 43210") })
        val retry = repo.schedule(OutboxKind.FOLLOWUP_CHECK, "l1", 0)
        val dead = repo.schedule(OutboxKind.OPEN_NEXT_APP, "l1", 0)
        val failing = repo.schedule(OutboxKind.REMINDER, "l1", 0)

        val report = driver.tick()

        assertEquals(2, report.retried)
        assertEquals(1, report.dead)
        assertEquals(1, db.outboxDao().get(retry.id)!!.attempts)
        assertEquals(OutboxStatus.DEAD, db.outboxDao().get(dead.id)!!.status)
        val failed = db.outboxDao().get(failing.id)!!
        assertEquals(1, failed.attempts)
        assertEquals(clock.elapsed + OutboxDriver.backoffMs(0), failed.dueElapsedMs)
        // The crash report has the class name and stack only, never the message text.
        val recorded = crashBackend.recorded.single()
        assertEquals("java.lang.IllegalStateException", recorded.message)
        assertEquals("OUTBOX", crashBackend.keys["LAST_SITE"])
    }

    @Test
    fun `items are marked dead after the maximum attempts`() = runBlocking {
        driver.register(setOf(OutboxKind.FOLLOWUP_CHECK), recordingHandler { OutboxOutcome.Retry(0) })
        val item = repo.schedule(OutboxKind.FOLLOWUP_CHECK, "l1", 0)
        driver.tick()
        driver.tick()
        assertEquals(1, driver.tick().dead)
        assertEquals(OutboxStatus.DEAD, db.outboxDao().get(item.id)!!.status)
        assertEquals(3, delivered.size)
    }

    @Test
    fun `items without a handler stay pending until one registers`() = runBlocking {
        val item = repo.schedule(OutboxKind.ERASE_SERVER, null, 0)
        assertEquals(1, driver.tick().unhandled)
        assertFalse(driver.hasHandler(OutboxKind.ERASE_SERVER))
        val registration = driver.register(setOf(OutboxKind.ERASE_SERVER), recordingHandler())
        assertTrue(driver.hasHandler(OutboxKind.ERASE_SERVER))
        assertEquals(1, driver.tick().delivered)
        assertEquals(item.id, delivered.single().id)
        registration.close()
        assertFalse(driver.hasHandler(OutboxKind.ERASE_SERVER))
    }

    @Test
    fun `the WorkManager backstop runs only while no session is active`() = runBlocking {
        val scheduler = RecordingScheduler()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            driver.start(scope, scheduler)
            scheduler.await("cancel")

            val reminder = repo.schedule(OutboxKind.REMINDER, "l1", 30_000)
            scheduler.await("at:${reminder.dueElapsedMs}:false")

            session.setActive(true)
            scheduler.await("cancel", occurrence = 2)

            session.setActive(false)
            repo.complete(reminder)
            repo.schedule(OutboxKind.ERASE_SERVER, null, 5_000)
            scheduler.await("at:${clock.elapsed + 5_000}:true")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `backoff doubles from 30 s up to one hour`() {
        assertEquals(30_000L, OutboxDriver.backoffMs(0))
        assertEquals(60_000L, OutboxDriver.backoffMs(1))
        assertEquals(120_000L, OutboxDriver.backoffMs(2))
        assertEquals(3_600_000L, OutboxDriver.backoffMs(7))
        assertEquals(3_600_000L, OutboxDriver.backoffMs(50))
    }

    class RecordingScheduler : OutboxWorkScheduler {
        val events = CopyOnWriteArrayList<String>()

        override fun scheduleAt(dueElapsedMs: Long, needsNetwork: Boolean) {
            events += "at:$dueElapsedMs:$needsNetwork"
        }

        override fun cancel() {
            events += "cancel"
        }

        suspend fun await(event: String, occurrence: Int = 1) = withTimeout(5_000) {
            while (events.count { it == event } < occurrence) delay(5)
        }
    }
}
