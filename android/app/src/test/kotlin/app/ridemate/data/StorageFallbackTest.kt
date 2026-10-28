package app.ridemate.data

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.testing.FakeClock
import app.ridemate.testing.TestData
import app.ridemate.testing.TestSeams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageFallbackTest {
    private lateinit var db: RideMateDb

    @Before
    fun setUp() {
        db = TestSeams.inMemoryDb(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `storage failures are classified and reported`() {
        assertTrue(StorageHealth.isStorageFailure(SQLiteFullException()))
        assertTrue(StorageHealth.isStorageFailure(SQLiteDiskIOException()))
        assertFalse(StorageHealth.isStorageFailure(SQLiteConstraintException()))
        assertFalse(StorageHealth.isStorageFailure(IllegalStateException()))

        val health = StorageHealth()
        assertEquals(StorageWarning.NONE, health.warning.value)
        health.onWriteFailed(SQLiteFullException())
        assertEquals(StorageWarning.FULL, health.warning.value)
        health.onWriteFailed(SQLiteDiskIOException())
        assertEquals(StorageWarning.WRITE_FAILED, health.warning.value)
        health.onWriteFailed(SQLiteDiskIOException("disk I/O error: SQLITE_FULL"))
        assertEquals(StorageWarning.FULL, health.warning.value)
        health.onWriteSucceeded()
        assertEquals(StorageWarning.NONE, health.warning.value)
    }

    @Test
    fun `guardian snapshots fall back to memory when the disk is full`() = runBlocking {
        db.sessionDao().insert(TestData.session())
        val dao = FlakySnapshotDao(db.guardianSnapshotDao())
        val health = StorageHealth()
        val store = GuardianSnapshotStore(dao, FakeClock(), health)

        assertTrue(store.save("s1", """{"v":1}"""))
        dao.fail = true
        assertFalse(store.save("s1", """{"v":2}"""))
        assertEquals(StorageWarning.FULL, health.warning.value)
        assertEquals("""{"v":2}""", store.load("s1"))
        assertEquals("""{"v":2}""", store.loadActive().single().json)

        dao.fail = false
        assertTrue(store.save("s1", """{"v":3}"""))
        assertEquals(StorageWarning.NONE, health.warning.value)
        assertEquals("""{"v":3}""", store.load("s1"))
        store.delete("s1")
        assertEquals(null, store.load("s1"))
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `programming errors are not hidden by the fallback`() = runBlocking {
        // No session row: the foreign key rejects the snapshot, and that must surface.
        GuardianSnapshotStore(db.guardianSnapshotDao(), FakeClock(), StorageHealth()).save("missing", "{}")
        Unit
    }

    private class FlakySnapshotDao(private val real: GuardianSnapshotDao) : GuardianSnapshotDao by real {
        var fail = false

        override suspend fun upsert(row: GuardianSnapshotEntity) {
            if (fail) throw SQLiteFullException("database or disk is full")
            real.upsert(row)
        }

        override fun observe(sessionId: String): Flow<GuardianSnapshotEntity?> = real.observe(sessionId)
    }
}
