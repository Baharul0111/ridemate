package app.ridemate.outbox

import android.database.sqlite.SQLiteFullException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.data.ActionOutboxDao
import app.ridemate.data.ActionOutboxEntity
import app.ridemate.data.OutboxKind
import app.ridemate.data.OutboxStatus
import app.ridemate.data.RideMateDb
import app.ridemate.data.StorageHealth
import app.ridemate.data.StorageWarning
import app.ridemate.testing.FakeBootIds
import app.ridemate.testing.FakeClock
import app.ridemate.testing.TestData
import app.ridemate.testing.TestSeams
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutboxRepositoryTest {
    private lateinit var db: RideMateDb
    private lateinit var dao: FlakyOutboxDao
    private val clock = FakeClock(elapsed = 50_000L, wall = 1_793_000_000_000L)
    private val boot = FakeBootIds("boot-1")
    private val health = StorageHealth()

    @Before
    fun setUp() = runBlocking {
        db = TestSeams.inMemoryDb(ApplicationProvider.getApplicationContext())
        db.sessionDao().insert(TestData.session())
        db.legDao().upsert(TestData.leg("l1"))
        db.legDao().upsert(TestData.leg("l2"))
        dao = FlakyOutboxDao(db.outboxDao())
    }

    @After
    fun tearDown() = db.close()

    private fun repo() = OutboxRepository(dao, clock, boot, health)

    @Test
    fun `schedule stores due time on both clocks and fires when due`() = runBlocking {
        val repo = repo()
        val item = repo.schedule(OutboxKind.REMINDER, "l1", delayMs = 30_000, payload = """{"n":1}""")

        assertTrue(item.id > 0)
        assertEquals(80_000L, item.dueElapsedMs)
        val row = db.outboxDao().get(item.id)!!
        assertEquals(clock.wall + 30_000, row.dueWallMs)
        assertEquals("boot-1", row.bootId)
        assertEquals(OutboxStatus.PENDING, row.status)

        assertTrue(repo.due().isEmpty())
        clock.advance(30_000)
        assertEquals(listOf(item), repo.due())
        assertEquals(80_000L, repo.nextDueElapsedMs())

        repo.complete(item)
        assertTrue(repo.pending().isEmpty())
        assertEquals(OutboxStatus.DONE, db.outboxDao().get(item.id)!!.status)
    }

    @Test
    fun `retry later counts attempts and kill marks dead`() = runBlocking {
        val repo = repo()
        val item = repo.schedule(OutboxKind.FOLLOWUP_CHECK, "l1", 0)
        repo.retryLater(item, 60_000)
        val moved = repo.pending().single()
        assertEquals(1, moved.attempts)
        assertEquals(clock.elapsed + 60_000, moved.dueElapsedMs)
        repo.kill(moved)
        assertEquals(OutboxStatus.DEAD, db.outboxDao().get(item.id)!!.status)
        assertTrue(repo.pending().isEmpty())
    }

    @Test
    fun `complete for leg clears only that leg's kinds`() = runBlocking {
        val repo = repo()
        repo.schedule(OutboxKind.REMINDER, "l1", 0)
        repo.schedule(OutboxKind.OPEN_NEXT_APP, "l1", 0)
        repo.schedule(OutboxKind.REMINDER, "l2", 0)
        repo.schedule(OutboxKind.ERASE_SERVER, null, 0)
        repo.completeForLeg("l1", setOf(OutboxKind.REMINDER))
        assertEquals(
            listOf(OutboxKind.OPEN_NEXT_APP to "l1", OutboxKind.REMINDER to "l2", OutboxKind.ERASE_SERVER to null),
            repo.pending().map { it.kind to it.legId },
        )
        repo.completeForLeg("l1")
        assertEquals(1, repo.countPending(OutboxKind.REMINDER))
        assertEquals(1, repo.countPending(OutboxKind.ERASE_SERVER))
    }

    @Test
    fun `rows survive process death`() = runBlocking {
        repo().schedule(OutboxKind.REMINDER, "l1", 30_000)
        repo().schedule(OutboxKind.ERASE_SERVER, null, 0, payload = """{"uid":"u"}""")

        // A new repository (new process) over the same database sees both rows.
        val reloaded = OutboxRepository(db.outboxDao(), clock, boot, StorageHealth())
        assertEquals(listOf(OutboxKind.ERASE_SERVER, OutboxKind.REMINDER), reloaded.pending().map { it.kind })
        assertEquals("""{"uid":"u"}""", reloaded.pending().first().payload)
    }

    @Test
    fun `rows from an earlier boot are rebased by wall clock`() = runBlocking {
        val repo = repo()
        repo.schedule(OutboxKind.REMINDER, "l1", 120_000) // due in 2 min
        repo.schedule(OutboxKind.FOLLOWUP_CHECK, "l1", 10_000) // due in 10 s

        // Reboot 60 s later: elapsed restarts near zero, the wall clock keeps going.
        boot.id = "boot-2"
        clock.elapsed = 5_000L
        clock.wall += 60_000

        assertEquals(2, repo.rebaseAfterReboot())
        val pending = repo.pending()
        assertEquals(OutboxKind.FOLLOWUP_CHECK, pending[0].kind)
        assertEquals("overdue row is due now", 0L, pending[0].dueElapsedMs)
        assertTrue(pending[0].dueElapsedMs <= clock.elapsed)
        assertEquals(5_000L + 60_000L, pending[1].dueElapsedMs)
        assertEquals(0, repo.rebaseAfterReboot())
        assertEquals(listOf("boot-2", "boot-2"), db.outboxDao().pending().map { it.bootId })
    }

    @Test
    fun `a tick that comes before the start-up rebase still uses the rebased due times`() = runBlocking {
        repo().schedule(OutboxKind.REMINDER, "l1", 120_000) // due in 2 min: elapsed 170 000 on boot-1

        // Reboot 60 s later; the new boot's elapsed clock is already past the old due value.
        boot.id = "boot-2"
        clock.elapsed = 200_000L
        clock.wall += 60_000
        val newProcess = OutboxRepository(db.outboxDao(), clock, boot, StorageHealth())

        assertTrue("not fired on the previous boot's clock", newProcess.due().isEmpty())
        assertEquals(260_000L, newProcess.pending().single().dueElapsedMs)
        clock.advance(60_000)
        assertEquals(OutboxKind.REMINDER, newProcess.due().single().kind)
        assertEquals(0, newProcess.rebaseAfterReboot())
    }

    @Test
    fun `the storage warning stays up while any row is held only in memory`() = runBlocking {
        val repo = repo()
        dao.failWrites = true
        repo.schedule(OutboxKind.REMINDER, "l1", 0)
        assertTrue(repo.hasUnsavedChanges())

        // The disk takes writes again: a new row lands in Room, but the first one is still memory-only.
        dao.failWrites = false
        assertTrue(repo.schedule(OutboxKind.FOLLOWUP_CHECK, "l1", 0).id > 0)
        assertEquals(StorageWarning.FULL, health.warning.value)

        assertEquals(1, repo.flushMemory())
        assertTrue(!repo.hasUnsavedChanges())
        assertEquals(StorageWarning.NONE, health.warning.value)
        assertEquals(2, db.outboxDao().count())
    }

    @Test
    fun `a full disk keeps rows in memory, warns, and flushes later`() = runBlocking {
        val repo = repo()
        dao.failWrites = true

        val item = repo.schedule(OutboxKind.REMINDER, "l1", 0)

        assertTrue(item.inMemoryOnly)
        assertEquals(StorageWarning.FULL, health.warning.value)
        assertEquals(listOf(item), repo.due())
        assertEquals(listOf(item), repo.observePending().first())
        assertEquals(0, db.outboxDao().count())

        // Still failing: flush stops without losing the row.
        assertEquals(0, repo.flushMemory())
        assertEquals(1, repo.pending().size)

        dao.failWrites = false
        assertEquals(1, repo.flushMemory())
        assertEquals(StorageWarning.NONE, health.warning.value)
        val persisted = repo.pending().single()
        assertTrue(persisted.id > 0)
        assertEquals(OutboxKind.REMINDER, persisted.kind)
    }

    @Test
    fun `a completion that cannot be written is never delivered twice`() = runBlocking {
        val repo = repo()
        val item = repo.schedule(OutboxKind.REMINDER, "l1", 0)
        dao.failWrites = true

        repo.complete(item)

        assertTrue(repo.due().isEmpty())
        assertEquals(StorageWarning.FULL, health.warning.value)
        assertEquals(OutboxStatus.PENDING, db.outboxDao().get(item.id)!!.status)

        dao.failWrites = false
        assertEquals(1, repo.flushMemory())
        assertEquals(OutboxStatus.DONE, db.outboxDao().get(item.id)!!.status)
    }

    @Test
    fun `a retry that cannot be written is shadowed in memory`() = runBlocking {
        val repo = repo()
        val item = repo.schedule(OutboxKind.FOLLOWUP_CHECK, "l1", 0)
        dao.failWrites = true

        repo.retryLater(item, 60_000)

        val pending = repo.pending().single()
        assertTrue(pending.inMemoryOnly)
        assertEquals(1, pending.attempts)
        assertTrue(repo.due().isEmpty())

        dao.failWrites = false
        assertEquals(2, repo.flushMemory())
        assertEquals(OutboxStatus.DONE, db.outboxDao().get(item.id)!!.status)
        assertEquals(1, repo.pending().single().attempts)
    }

    @Test
    fun `memory rows of a deleted leg are dropped on flush`() = runBlocking {
        val repo = repo()
        dao.failWrites = true
        repo.schedule(OutboxKind.REMINDER, "gone-leg", 0)
        dao.failWrites = false
        assertEquals(0, repo.flushMemory())
        assertTrue(repo.pending().isEmpty())
        repo.clearMemory()
        assertNull(repo.nextDueElapsedMs())
    }

    /** Delegates to Room, but throws SQLITE_FULL on writes while [failWrites]. */
    class FlakyOutboxDao(private val real: ActionOutboxDao) : ActionOutboxDao by real {
        var failWrites = false

        private fun check() {
            if (failWrites) throw SQLiteFullException("database or disk is full")
        }

        override suspend fun insert(row: ActionOutboxEntity): Long {
            check(); return real.insert(row)
        }

        override suspend fun update(row: ActionOutboxEntity) {
            check(); real.update(row)
        }

        override suspend fun completeForLeg(legId: String, kinds: List<OutboxKind>): Int {
            check(); return real.completeForLeg(legId, kinds)
        }

        override suspend fun complete(id: Long): Int {
            check(); return real.complete(id)
        }
    }
}
