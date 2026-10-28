package app.ridemate

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.ridemate.crash.CrashReporter
import app.ridemate.data.OutboxKind
import app.ridemate.data.PurgeWorker
import app.ridemate.data.SessionStatus
import app.ridemate.net.ApiResult
import app.ridemate.testing.TestData
import app.ridemate.testing.TestRideMateApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppGraphTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as TestRideMateApp
    private val savedHandler = Thread.getDefaultUncaughtExceptionHandler()

    @After
    fun restore() = Thread.setDefaultUncaughtExceptionHandler(savedHandler)

    @Test
    fun `the graph is reachable from any context and its members are singletons`() {
        val graph = RideMateApp.graph(context)
        assertSame(graph, app.graph)
        assertSame(graph.db, graph.db)
        assertSame(graph.apiClient, graph.apiClient)
        assertSame(graph.authGateway, graph.authGateway)
        assertSame(graph.configRepository, graph.configRepository)
        assertSame(graph.outboxDriver, graph.outboxDriver)
        assertTrue(graph.localWipers.isNotEmpty())
    }

    @Test
    fun `debug builds never auto-collect crash reports, even before start`() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertEquals(false, info.metaData.getBoolean("firebase_crashlytics_collection_enabled", true))
    }

    @Test
    fun `the api client reads its base url from remote config`() = runBlocking {
        val graph = app.graph
        assertEquals(ApiResult.NotConfigured, graph.apiClient.configCheck())
        // Configured but signed out: no request is made, sign-in is needed.
        app.fakes.remoteConfig.active["api_base_url"] = "https://ridemate.invalid"
        assertEquals(ApiResult.AuthNeeded, graph.apiClient.configCheck())
    }

    @Test
    fun `start wires crash reporting, config warm-up, purge and the outbox`() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val graph = app.graph
        graph.db.sessionDao().insert(
            TestData.session("old", status = SessionStatus.CLOSED, closedAtUtc = app.fakes.clock.wall - 25 * 3_600_000L),
        )

        graph.start(foreground = null)
        graph.start(foreground = null) // idempotent

        // Debug build: crash collection off; fatal crashes still pass through the sanitizer.
        assertEquals(false, app.fakes.crash.collectionEnabled)
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() is CrashReporter.SanitizingHandler)
        // Remote Config: fetched in the background (never awaited) with a 5 s timeout and a short debug interval.
        withTimeout(5_000) { while (app.fakes.remoteConfig.fetchCount == 0) delay(5) }
        assertEquals(5L, app.fakes.remoteConfig.configuredTimeoutS)
        assertEquals(AppGraph.DEBUG_MIN_FETCH_INTERVAL_S, app.fakes.remoteConfig.configuredMinIntervalS)
        // The ERASE_SERVER retry handler is registered.
        assertTrue(graph.outboxDriver.hasHandler(OutboxKind.ERASE_SERVER))
        // The 24 h purge ran on start and is scheduled periodically.
        withTimeout(5_000) { while (graph.db.sessionDao().get("old") != null) delay(5) }
        assertNull(graph.db.sessionDao().get("old"))
        val purge = WorkManager.getInstance(context).getWorkInfosForUniqueWork(PurgeWorker.UNIQUE_NAME).get()
        // Periodic work is never "finished": it is enqueued, or running right now on the test executor.
        assertTrue(purge.single().state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING))
        // Auth kind is mirrored into an enum-only crash key.
        withTimeout(5_000) { while (app.fakes.crash.keys["AUTH_KIND"] == null) delay(5) }
        assertEquals("SIGNED_OUT", app.fakes.crash.keys["AUTH_KIND"])
    }
}
