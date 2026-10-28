package app.ridemate.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass
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
class RideMateDbTest {
    private lateinit var db: RideMateDb

    @Before
    fun setUp() {
        db = TestSeams.inMemoryDb(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `entities round-trip with enums and lists stored as text`() = runBlocking {
        db.sessionDao().insert(TestData.session())
        db.legDao().upsert(TestData.leg())
        val session = db.sessionDao().get("s1")!!
        assertEquals(listOf(Provider.UBER, Provider.RAPIDO), session.selectedApps)
        assertEquals(listOf(VehicleClass.AUTO, VehicleClass.CAB_ECONOMY), session.vehicleClasses)
        assertEquals("LGBI Airport, Guwahati", session.drop.toPlace().label)
        assertEquals(TestData.leg(), db.legDao().get("l1"))

        db.query("SELECT selected_apps, status FROM session", null).use { c ->
            c.moveToFirst()
            assertEquals("UBER,RAPIDO", c.getString(0))
            assertEquals("ACTIVE", c.getString(1))
        }
        db.query("SELECT provider, state, eta_source FROM leg", null).use { c ->
            c.moveToFirst()
            assertEquals("RAPIDO", c.getString(0))
            assertEquals("SEARCHING", c.getString(1))
            assertEquals("TEMPLATE", c.getString(2))
        }
    }

    @Test
    fun `flow queries re-emit on change`() = runBlocking {
        db.sessionDao().insert(TestData.session())
        assertEquals(1, db.sessionDao().observeActive().first().size)
        db.legDao().upsert(TestData.leg())
        assertEquals(RideState.SEARCHING, db.legDao().observeForSession("s1").first().single().state)
        db.legDao().upsert(TestData.leg(state = RideState.ASSIGNED))
        assertEquals(RideState.ASSIGNED, db.legDao().observe("l1").first()!!.state)
        db.quoteDao().insert(TestData.quote(frameHash = "abc"))
        assertEquals("abc", db.quoteDao().observeForSession("s1").first().single().sourceFrameHash)
        assertEquals(1, db.sessionDao().close("s1", 1_793_000_100_000L))
        assertEquals(0, db.sessionDao().close("s1", 1_793_000_200_000L))
        assertTrue(db.sessionDao().observeActive().first().isEmpty())
        assertEquals(SessionStatus.CLOSED, db.sessionDao().observe("s1").first()!!.status)
    }

    @Test
    fun `deleting a session cascades to every child table`() = runBlocking {
        db.sessionDao().insert(TestData.session())
        db.legDao().upsert(TestData.leg())
        db.quoteDao().insert(TestData.quote())
        db.evidenceDao().insert(TestData.evidence())
        db.outboxDao().insert(outboxRow(legId = "l1"))
        db.postFingerprintDao().upsert(TestData.fingerprint())
        db.classifierCacheDao().upsert(TestData.classification())
        db.guardianSnapshotDao().upsert(GuardianSnapshotEntity("s1", "{}", 1L))

        db.sessionDao().close("s1", 0L)
        assertEquals(1, db.sessionDao().deleteClosedBefore(1L))

        assertEquals(0, db.legDao().count())
        assertEquals(0, db.quoteDao().count())
        assertEquals(0, db.evidenceDao().count())
        assertEquals(0, db.outboxDao().count())
        assertEquals(0, db.postFingerprintDao().count())
        assertEquals(0, db.classifierCacheDao().count())
        assertEquals(0, db.guardianSnapshotDao().count())
    }

    @Test
    fun `guardian snapshots of active sessions only`() = runBlocking {
        db.sessionDao().insert(TestData.session("s1"))
        db.sessionDao().insert(TestData.session("s2"))
        db.guardianSnapshotDao().upsert(GuardianSnapshotEntity("s1", """{"v":1}""", 1L))
        db.guardianSnapshotDao().upsert(GuardianSnapshotEntity("s2", """{"v":2}""", 2L))
        db.sessionDao().close("s2", 3L)
        assertEquals(listOf("s1"), db.guardianSnapshotDao().forActiveSessions().map { it.sessionId })
        db.guardianSnapshotDao().upsert(GuardianSnapshotEntity("s1", """{"v":3}""", 4L))
        assertEquals("""{"v":3}""", db.guardianSnapshotDao().get("s1")!!.json)
    }

    @Test
    fun `classifier cache and fingerprints are keyed as specified`() = runBlocking {
        db.sessionDao().insert(TestData.session())
        db.classifierCacheDao().upsert(TestData.classification())
        db.classifierCacheDao().upsert(TestData.classification().copy(event = "searching"))
        assertEquals(1, db.classifierCacheDao().count())
        assertEquals("searching", db.classifierCacheDao().get("com.rapido.passenger", 12345L, "captain is on the way")!!.event)
        assertNull(db.classifierCacheDao().get("com.rapido.passenger", 1L, "captain is on the way"))

        db.postFingerprintDao().upsert(TestData.fingerprint())
        db.postFingerprintDao().upsert(TestData.fingerprint().copy(lastFingerprint = "fp-2"))
        assertEquals("fp-2", db.postFingerprintDao().get("com.rapido.passenger|k1")!!.lastFingerprint)
    }

    @Test
    fun `recorder log keeps posts and removals in time order`() = runBlocking {
        db.recorderPostDao().insert(recorderPost(at = 2L, removal = 2))
        db.recorderPostDao().insert(recorderPost(at = 1L, removal = null))
        assertEquals(listOf(1L, 2L), db.recorderPostDao().all().map { it.atUtc })
        assertEquals(2, db.recorderPostDao().observeLatest(10).first().first().removalReason)
        assertEquals(2, db.recorderPostDao().clear())
    }

    private fun recorderPost(at: Long, removal: Int?) = RecorderPostEntity(
        packageName = "com.ubercab", channelId = "trip", category = "status", isOngoing = true, notifId = 7, tag = null,
        titleMasked = "Driver <m>", textMasked = "arriving in # min", redacted = false, removalReason = removal, atUtc = at,
    )

    companion object {
        fun outboxRow(legId: String?, kind: OutboxKind = OutboxKind.REMINDER, status: OutboxStatus = OutboxStatus.PENDING, createdAt: Long = 0L) =
            ActionOutboxEntity(
                legId = legId, kind = kind, dueElapsedMs = 10L, dueWallMs = 10L, bootId = "boot-1", attempts = 0,
                status = status, payload = null, createdAtUtc = createdAt,
            )
    }
}
