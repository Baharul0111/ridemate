package app.ridemate.privacy

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.auth.AuthFailure
import app.ridemate.auth.DefaultAuthGateway
import app.ridemate.crash.CrashBackend
import app.ridemate.crash.CrashReporter
import app.ridemate.data.OutboxKind
import app.ridemate.data.RideMateDb
import app.ridemate.data.StorageHealth
import app.ridemate.net.ApiResult
import app.ridemate.outbox.OutboxDriver
import app.ridemate.outbox.OutboxOutcome
import app.ridemate.outbox.OutboxRepository
import app.ridemate.outbox.TickReport
import app.ridemate.testing.FakeApiClient
import app.ridemate.testing.FakeAuthBackend
import app.ridemate.testing.FakeBootIds
import app.ridemate.testing.FakeClock
import app.ridemate.testing.FakeGoogleCredentialSource
import app.ridemate.testing.TestData
import app.ridemate.testing.TestSeams
import app.ridemate.util.SessionActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class EraseUseCaseTest {
    private val log = CopyOnWriteArrayList<String>()
    private lateinit var db: RideMateDb
    private val clock = FakeClock()
    private val auth = FakeAuthBackend(log = log)
    private val api = FakeApiClient(log)
    private lateinit var outbox: OutboxRepository
    private lateinit var gateway: DefaultAuthGateway
    private lateinit var erase: EraseUseCase

    private val crashBackend = object : CrashBackend {
        override fun setCollectionEnabled(enabled: Boolean) = Unit
        override fun recordException(throwable: Throwable) {
            log += "crash.record"
        }
        override fun setCustomKey(key: String, value: String) = Unit
        override fun setUserId(id: String) {
            log += "crash.userId($id)"
        }
        override fun deleteUnsentReports() {
            log += "crash.deleteUnsent"
        }
        override fun log(message: String) = Unit
    }

    @Before
    fun setUp() = runBlocking {
        db = TestSeams.inMemoryDb(ApplicationProvider.getApplicationContext())
        db.sessionDao().insert(TestData.session())
        db.legDao().upsert(TestData.leg())
        db.quoteDao().insert(TestData.quote())
        outbox = OutboxRepository(db.outboxDao(), clock, FakeBootIds(), StorageHealth())
        outbox.schedule(OutboxKind.REMINDER, "l1", 30_000)
        Unit
    }

    @After
    fun tearDown() = db.close()

    private fun build(wipers: List<LocalDataWiper> = emptyList()) {
        gateway = DefaultAuthGateway(auth, FakeGoogleCredentialSource(), clock)
        erase = EraseUseCase(
            wipeDatabase = { log += "wipe"; db.clearAllTables() },
            outbox = outbox,
            api = api,
            auth = gateway,
            crash = CrashReporter { crashBackend },
            extraWipers = { wipers },
        )
        log.clear()
    }

    @Test
    fun `google user - wipe, server erase, delete account, clear crash id, sign out - in that order`() = runBlocking {
        auth.user = FakeAuthBackend.googleUser()
        build(listOf(LocalDataWiper { log += "extraWipe" }, LocalDataWiper { error("broken store") }))

        val result = erase.erase()

        assertEquals(EraseResult(ServerErase.DONE, AccountErase.DELETED, signedOut = true), result)
        // A failing extra store is reported (sanitized) but does not stop the erase.
        assertEquals(
            listOf(
                "wipe", "extraWipe", "crash.record",
                "api.erase",
                "crash.userId()", "crash.deleteUnsent",
                "deleteUser", "signOut",
                "crash.userId()", "crash.deleteUnsent",
                "signOut",
            ),
            log,
        )
        assertEquals(0, db.sessionDao().count())
        assertEquals(0, db.legDao().count())
        assertEquals(0, db.quoteDao().count())
        assertEquals(0, db.outboxDao().count())
        assertNull(auth.user)
        assertEquals(EraseStatus.Finished(result), erase.status.value)
    }

    @Test
    fun `server down - local data gone, erase queued, account kept for the retry`() = runBlocking {
        auth.user = FakeAuthBackend.googleUser("u-1")
        build()
        api.eraseResults += ApiResult.Offline

        val result = erase.erase()

        assertEquals(EraseResult(ServerErase.QUEUED, AccountErase.WAITING_FOR_SERVER, signedOut = false), result)
        assertEquals(EraseStatus.WaitingForServer, erase.status.value)
        assertEquals(0, db.sessionDao().count())
        val queued = outbox.pending().single()
        assertEquals(OutboxKind.ERASE_SERVER, queued.kind)
        assertEquals("""{"uid":"u-1"}""", queued.payload)
        assertTrue(auth.user != null)
        assertTrue(!log.contains("deleteUser"))

        // The outbox retries later; the server answers and the account deletion completes.
        val driver = OutboxDriver(outbox, clock, SessionActivity())
        erase.registerWith(driver)
        api.eraseResults += ApiResult.Http(503)
        assertEquals(TickReport(nextDueElapsedMs = queued.dueElapsedMs), driver.tick())
        clock.advance(EraseUseCase.FIRST_RETRY_DELAY_MS)
        assertEquals(1, driver.tick().retried)
        clock.advance(OutboxDriver.backoffMs(0))
        assertEquals(1, driver.tick().delivered)
        assertNull(auth.user)
        assertTrue(outbox.pending().isEmpty())
        assertEquals(EraseStatus.Finished(EraseResult(ServerErase.DONE, AccountErase.DELETED, signedOut = true)), erase.status.value)
    }

    @Test
    fun `a queued erase for another account is dropped`() = runBlocking {
        auth.user = FakeAuthBackend.googleUser("u-1")
        build()
        api.eraseResults += ApiResult.Timeout
        erase.erase()
        val queued = outbox.pending().single()

        auth.user = FakeAuthBackend.googleUser("u-2")
        assertEquals(OutboxOutcome.Dead, erase.handle(queued))
        auth.user = null
        assertEquals(OutboxOutcome.Dead, erase.handle(queued))
        assertTrue(!log.contains("deleteUser"))
    }

    @Test
    fun `a refused server erase still deletes the account and points to the manual runbook`() = runBlocking {
        auth.user = FakeAuthBackend.googleUser()
        build()
        api.eraseResults += ApiResult.AuthNeeded
        val result = erase.erase()
        assertEquals(EraseResult(ServerErase.FAILED, AccountErase.DELETED, signedOut = true), result)
        assertTrue(outbox.pending().isEmpty())
    }

    @Test
    fun `anonymous users have no server data`() = runBlocking {
        auth.user = FakeAuthBackend.anonymousUser()
        build()
        val result = erase.erase()
        assertEquals(EraseResult(ServerErase.SKIPPED, AccountErase.DELETED, signedOut = true), result)
        assertTrue(!log.contains("api.erase"))
    }

    @Test
    fun `signed out users only lose local data`() = runBlocking {
        build()
        val result = erase.erase()
        assertEquals(EraseResult(ServerErase.SKIPPED, AccountErase.SKIPPED, signedOut = true), result)
        assertEquals(listOf("wipe", "crash.userId()", "crash.deleteUnsent"), log)
        assertEquals(0, db.sessionDao().count())
    }

    @Test
    fun `firebase asking for a fresh sign-in keeps the user signed in`() = runBlocking {
        auth.user = FakeAuthBackend.googleUser()
        auth.deleteFailure = AuthFailure.RECENT_LOGIN_REQUIRED
        build()
        val result = erase.erase()
        assertEquals(EraseResult(ServerErase.DONE, AccountErase.REAUTH_REQUIRED, signedOut = false), result)
        assertEquals(EraseStatus.NeedsReauth, erase.status.value)
        assertTrue(auth.user != null)

        // After signing in again, erasing again finishes.
        auth.deleteFailure = null
        assertEquals(AccountErase.DELETED, erase.erase().account)
    }

    @Test
    fun `no server configured means nothing to erase there`() = runBlocking {
        auth.user = FakeAuthBackend.googleUser()
        build()
        api.eraseResults += ApiResult.NotConfigured
        assertEquals(EraseResult(ServerErase.SKIPPED, AccountErase.DELETED, signedOut = true), erase.erase())
    }
}
