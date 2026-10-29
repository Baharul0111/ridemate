package app.ridemate.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.ridemate.RideMateApp
import app.ridemate.testing.FakeClock
import app.ridemate.testing.TestData
import app.ridemate.testing.TestRideMateApp
import app.ridemate.testing.TestSeams
import app.ridemate.trip.RecentPlacesStore
import app.ridemate.trip.TripPlace
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetentionPurgerTest {
    private val hour = 60 * 60 * 1000L
    private lateinit var db: RideMateDb
    private val clock = FakeClock(wall = 1_793_000_000_000L)

    @Before
    fun setUp() {
        db = TestSeams.inMemoryDb(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `sessions closed more than 24 h ago go with all their children`() = runBlocking {
        val now = clock.wall
        seedSession("old", closedAt = now - 25 * hour)
        seedSession("recent", closedAt = now - 23 * hour)
        seedSession("active", closedAt = null, createdAt = now - 2 * hour)

        val report = RetentionPurger(db, clock).purge()

        assertEquals(1, report.closedSessions)
        assertNull(db.sessionDao().get("old"))
        assertNotNull(db.sessionDao().get("recent"))
        assertNotNull(db.sessionDao().get("active"))
        assertNull(db.legDao().get("old-leg"))
        assertEquals(2, db.legDao().count())
        assertEquals(2, db.quoteDao().count())
        assertEquals(2, db.evidenceDao().count())
        assertEquals(2, db.guardianSnapshotDao().count())
        assertEquals(2, db.postFingerprintDao().count())
        assertEquals(2, db.classifierCacheDao().count())
        assertEquals(2, db.outboxDao().count())
    }

    @Test
    fun `a closed session written without a close time is purged 24 h after creation`() = runBlocking {
        val now = clock.wall
        db.sessionDao().upsert(TestData.session("no-close-old", createdAtUtc = now - 25 * hour, status = SessionStatus.CLOSED))
        db.sessionDao().upsert(TestData.session("no-close-new", createdAtUtc = now - 23 * hour, status = SessionStatus.CLOSED))

        assertEquals(1, RetentionPurger(db, clock).purge().closedSessions)

        assertNull(db.sessionDao().get("no-close-old"))
        assertNotNull(db.sessionDao().get("no-close-new"))
    }

    @Test
    fun `abandoned active sessions and stale unscoped rows are removed`() = runBlocking {
        val now = clock.wall
        seedSession("abandoned", closedAt = null, createdAt = now - 49 * hour)
        db.postFingerprintDao().upsert(TestData.fingerprint(sessionId = null, lastSeen = now - 25 * hour, sbnKey = "old"))
        db.postFingerprintDao().upsert(TestData.fingerprint(sessionId = null, lastSeen = now - hour, sbnKey = "new"))
        db.classifierCacheDao().upsert(TestData.classification(sessionId = null, createdAt = now - 25 * hour, text = "old"))
        db.classifierCacheDao().upsert(TestData.classification(sessionId = null, createdAt = now - hour, text = "new"))
        db.outboxDao().insert(RideMateDbTest.outboxRow(null, OutboxKind.ERASE_SERVER, OutboxStatus.DONE, createdAt = now - 25 * hour))
        db.outboxDao().insert(RideMateDbTest.outboxRow(null, OutboxKind.ERASE_SERVER, OutboxStatus.PENDING, createdAt = now - 25 * hour))

        val report = RetentionPurger(db, clock).purge()

        assertEquals(1, report.abandonedSessions)
        assertEquals(1, report.unscopedFingerprints)
        assertEquals(1, report.unscopedClassifications)
        assertEquals(1, report.finishedOutboxRows)
        assertEquals(1, db.postFingerprintDao().count())
        assertEquals(1, db.classifierCacheDao().count())
        assertEquals(OutboxStatus.PENDING, db.outboxDao().pending().single().status)
    }

    @Test
    fun `purge worker runs the purge from the app graph and is scheduled once`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val app = context as TestRideMateApp
        val graph = RideMateApp.graph(context)
        val now = app.fakes.clock.wall
        graph.db.sessionDao().insert(TestData.session("old", status = SessionStatus.CLOSED, closedAtUtc = now - 30 * hour))

        val result = TestListenableWorkerBuilder<PurgeWorker>(context).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull(graph.db.sessionDao().get("old"))

        val workManager = WorkManager.getInstance(context)
        PurgeWorker.schedule(workManager)
        PurgeWorker.schedule(workManager)
        val infos = workManager.getWorkInfosForUniqueWork(PurgeWorker.UNIQUE_NAME).get()
        assertEquals(1, infos.size)
        // One unique periodic job (KEEP): enqueued, or already running on the test executor.
        assertTrue(infos.single().state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING))
    }

    @Test
    fun `the periodic purge also drops recent destinations older than 24 h, without RideMate being opened`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val app = context as TestRideMateApp
        val graph = RideMateApp.graph(context)
        val prefs = context.getSharedPreferences(RecentPlacesStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        graph.recentPlaces.add(TripPlace("Paltan Bazaar, Guwahati", null))
        app.fakes.clock.advance(1 * hour)
        graph.recentPlaces.add(TripPlace("LGBI Airport", null))
        // The phone sits unused for a day: nothing reads the store (a read would purge it by itself).
        app.fakes.clock.advance(24 * hour - 30 * 60_000L)
        val before = prefs.getString("recent", null).orEmpty()
        assertTrue(before.contains("Paltan Bazaar") && before.contains("LGBI Airport"))

        val result = TestListenableWorkerBuilder<PurgeWorker>(context).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val after = prefs.getString("recent", null).orEmpty()
        assertTrue("the 24.5 h old destination is gone from storage", !after.contains("Paltan Bazaar"))
        assertTrue("the 23.5 h old one stays until its own day is up", after.contains("LGBI Airport"))

        app.fakes.clock.advance(hour)
        TestListenableWorkerBuilder<PurgeWorker>(context).build().doWork()
        assertNull(prefs.getString("recent", null))
    }

    private suspend fun seedSession(id: String, closedAt: Long?, createdAt: Long = clock.wall - 30 * hour) {
        val status = if (closedAt == null) SessionStatus.ACTIVE else SessionStatus.CLOSED
        db.sessionDao().insert(TestData.session(id, createdAtUtc = createdAt, status = status, closedAtUtc = closedAt))
        val legId = "$id-leg"
        db.legDao().upsert(TestData.leg(legId, id))
        db.quoteDao().insert(TestData.quote(legId))
        db.evidenceDao().insert(TestData.evidence(legId))
        db.outboxDao().insert(RideMateDbTest.outboxRow(legId))
        db.postFingerprintDao().upsert(TestData.fingerprint(sessionId = id, sbnKey = id))
        db.classifierCacheDao().upsert(TestData.classification(sessionId = id, text = id))
        db.guardianSnapshotDao().upsert(GuardianSnapshotEntity(id, "{}", createdAt))
    }
}
