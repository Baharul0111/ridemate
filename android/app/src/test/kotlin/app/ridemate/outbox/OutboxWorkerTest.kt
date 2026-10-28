package app.ridemate.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.ridemate.RideMateApp
import app.ridemate.data.OutboxKind
import app.ridemate.testing.FakeClock
import app.ridemate.testing.TestData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class OutboxWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun `the worker fires due rows only when no session runs`() = runBlocking {
        val graph = RideMateApp.graph(context)
        graph.db.sessionDao().insert(TestData.session())
        graph.db.legDao().upsert(TestData.leg("l1"))
        val delivered = CopyOnWriteArrayList<OutboxItem>()
        graph.outboxDriver.register(setOf(OutboxKind.REMINDER)) { delivered += it; OutboxOutcome.Done }
        graph.outboxRepository.schedule(OutboxKind.REMINDER, "l1", 0)

        graph.sessionActivity.setActive(true)
        val whileActive = TestListenableWorkerBuilder<OutboxWorker>(context).build().doWork()
        assertEquals(ListenableWorker.Result.success(), whileActive)
        assertTrue("the service owns reminders during a session", delivered.isEmpty())

        graph.sessionActivity.setActive(false)
        val afterSession = TestListenableWorkerBuilder<OutboxWorker>(context).build().doWork()
        assertEquals(ListenableWorker.Result.success(), afterSession)
        assertEquals(1, delivered.size)
        assertTrue(graph.outboxRepository.pending().isEmpty())
    }

    @Test
    fun `the scheduler keeps one unique job and re-enqueues only on change`() {
        val workManager = WorkManager.getInstance(context)
        val clock = FakeClock(elapsed = 10_000L)
        val scheduler = WorkManagerOutboxScheduler({ workManager }, clock)

        scheduler.scheduleAt(40_000L, needsNetwork = false)
        val first = workManager.getWorkInfosForUniqueWork(OutboxWorker.UNIQUE_NAME).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertEquals(30_000L, first.initialDelayMillis)

        scheduler.scheduleAt(40_000L, needsNetwork = false)
        assertEquals(first.id, workManager.getWorkInfosForUniqueWork(OutboxWorker.UNIQUE_NAME).get().single().id)

        scheduler.scheduleAt(5_000L, needsNetwork = true)
        val replaced = workManager.getWorkInfosForUniqueWork(OutboxWorker.UNIQUE_NAME).get()
            .single { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(0L, replaced.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, replaced.constraints.requiredNetworkType)

        scheduler.cancel()
        assertTrue(workManager.getWorkInfosForUniqueWork(OutboxWorker.UNIQUE_NAME).get().all { it.state == WorkInfo.State.CANCELLED })
    }
}
