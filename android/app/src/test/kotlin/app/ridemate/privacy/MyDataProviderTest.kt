package app.ridemate.privacy

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.auth.AuthState
import app.ridemate.config.ConfigInfo
import app.ridemate.config.FetchStatus
import app.ridemate.data.GuardianSnapshotEntity
import app.ridemate.data.OutboxKind
import app.ridemate.data.RideMateDb
import app.ridemate.data.StorageHealth
import app.ridemate.outbox.OutboxRepository
import app.ridemate.testing.FakeBootIds
import app.ridemate.testing.FakeClock
import app.ridemate.testing.TestData
import app.ridemate.testing.TestSeams
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyDataProviderTest {
    private lateinit var db: RideMateDb
    private lateinit var outbox: OutboxRepository
    private var state: AuthState = AuthState.SignedOut
    private val info = ConfigInfo(1_793_000_000_000L, FetchStatus.SUCCESS, "abc123def456")

    @Before
    fun setUp() {
        db = TestSeams.inMemoryDb(ApplicationProvider.getApplicationContext())
        outbox = OutboxRepository(db.outboxDao(), FakeClock(), FakeBootIds(), StorageHealth())
    }

    @After
    fun tearDown() = db.close()

    private fun provider() = MyDataProvider(db, { state }, { info }, outbox)

    @Test
    fun `empty phone`() = runBlocking {
        val summary = provider().load()
        assertEquals(HeldData.entries.toList(), summary.counts.keys.toList())
        assertEquals(0, summary.totalRows)
        assertEquals(SignInKind.SIGNED_OUT, summary.signInKind)
        assertNull(summary.signInEmail)
        assertNull(summary.lastSessionAtUtc)
        assertEquals(info, summary.remoteConfig)
        assertEquals(false, summary.pendingServerErase)
    }

    @Test
    fun `counts every table and shows the google email`() = runBlocking {
        db.sessionDao().insert(TestData.session("s1", createdAtUtc = 10L))
        db.sessionDao().insert(TestData.session("s2", createdAtUtc = 20L))
        db.legDao().upsert(TestData.leg("l1", "s1"))
        db.quoteDao().insert(TestData.quote("l1"))
        db.quoteDao().insert(TestData.quote("l1"))
        db.evidenceDao().insert(TestData.evidence("l1"))
        db.postFingerprintDao().upsert(TestData.fingerprint())
        db.classifierCacheDao().upsert(TestData.classification())
        db.guardianSnapshotDao().upsert(GuardianSnapshotEntity("s1", "{}", 1L))
        outbox.schedule(OutboxKind.ERASE_SERVER, null, 0)
        state = AuthState.Google("rider@example.com", "Rider", null, "u")

        val summary = provider().load()

        assertEquals(2, summary.counts[HeldData.SESSIONS])
        assertEquals(1, summary.counts[HeldData.LEGS])
        assertEquals(2, summary.counts[HeldData.QUOTES])
        assertEquals(1, summary.counts[HeldData.EVIDENCE])
        assertEquals(1, summary.counts[HeldData.PENDING_ACTIONS])
        assertEquals(1, summary.counts[HeldData.NOTIFICATION_FINGERPRINTS])
        assertEquals(1, summary.counts[HeldData.CLASSIFIER_CACHE])
        assertEquals(1, summary.counts[HeldData.GUARDIAN_SNAPSHOTS])
        assertEquals(0, summary.counts[HeldData.RECORDER_POSTS])
        assertEquals(10, summary.totalRows)
        assertEquals(SignInKind.GOOGLE, summary.signInKind)
        assertEquals("rider@example.com", summary.signInEmail)
        assertEquals(20L, summary.lastSessionAtUtc)
        assertEquals(true, summary.pendingServerErase)
    }

    @Test
    fun `anonymous users show no email`() = runBlocking {
        state = AuthState.Anonymous("a")
        val summary = provider().load()
        assertEquals(SignInKind.ANONYMOUS, summary.signInKind)
        assertNull(summary.signInEmail)
    }
}
