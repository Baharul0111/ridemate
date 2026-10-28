package app.ridemate.session

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.guardian.UiAction
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Place
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.watch.PostSnapshot
import app.ridemate.core.watch.PostSource
import app.ridemate.data.OutboxKind
import app.ridemate.notify.DriverSheetItem
import app.ridemate.notify.GuardianIntents
import app.ridemate.notify.GuardianTap
import app.ridemate.notify.SessionOp
import app.ridemate.notify.SessionOpKind
import app.ridemate.notify.TapScope
import app.ridemate.testing.TestRideMateApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/** The session service, the notification-action receiver and the boot receiver, on the app's own graph. */
@RunWith(AndroidJUnit4::class)
class RideSessionServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as TestRideMateApp
    private val runtime get() = app.graph.guardianRuntime

    private val services = mutableListOf<ServiceController<RideSessionService>>()
    private val destroyed = mutableSetOf<ServiceController<RideSessionService>>()

    @Before
    fun setUp() {
        app.fakes.bundled = AssetGuardianConfig.bundled(context)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Services are destroyed after each test so the process-wide "running" reference never leaks between tests. */
    @After
    fun tearDown() {
        services.filter { it !in destroyed }.forEach { it.destroy() }
    }

    private fun startService(): ServiceController<RideSessionService> =
        Robolectric.buildService(RideSessionService::class.java).create().startCommand(0, 1).also { services += it }

    private fun destroy(controller: ServiceController<RideSessionService>) {
        controller.destroy()
        destroyed += controller
    }

    private fun idle() {
        runBlocking { withTimeout(20_000) { repeat(2) { runtime.awaitIdle() } } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Waits for work started outside the guardian (receivers run on their own coroutine). */
    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!check()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
            runBlocking { runtime.awaitIdle() }
        }
    }

    private fun spec(vararg providers: Provider) =
        SessionSpec(Place("Riverside Gate", LatLng(26.1445, 91.7362)), Place("Airport", LatLng(26.1112, 91.63442)), providers.toList(), listOf(VehicleClass.AUTO))

    private fun post(provider: Provider, key: String, content: Pair<String, String>, ongoing: Boolean = true) {
        runtime.onRideAppPost(
            PostSource.of(
                PostSnapshot(provider.packageName, key, 1, null, "ride", "status", ongoing, content.first, content.second, app.fakes.clock.wall, 100L),
            ),
        )
        idle()
    }

    private fun bookAndKeepRapido() {
        runtime.onListenerConnected(emptyList())
        runtime.bookedHere(Provider.UBER)
        runtime.bookedHere(Provider.RAPIDO)
        post(Provider.UBER, "u1", GuardianTestRig.UBER_SEARCHING)
        post(Provider.RAPIDO, "r1", GuardianTestRig.RAPIDO_SEARCHING)
        post(Provider.RAPIDO, "r1", GuardianTestRig.RAPIDO_ASSIGNED)
        val sheet = runtime.uiState.value.items.filterIsInstance<DriverSheetItem>().single()
        runtime.perform(GuardianTap(TapScope.SHEET, sheet.id, UiAction.YES_CANCEL_OTHERS))
        idle()
    }

    private val notificationManager get() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `the service runs as specialUse, stays while an extra booking is pending (I8), and stops once it is proven`() {
        runtime.start()
        runtime.startSession(spec(Provider.UBER, Provider.RAPIDO))
        idle()
        assertEquals(RideSessionService::class.java.name, shadowOf(context as Application).nextStartedService.component?.className)

        val controller = startService()
        val service = controller.get()
        idle()
        assertTrue(app.graph.sessionActivity.active.value)
        assertEquals(RideSessionService.ONGOING_NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)

        bookAndKeepRapido()
        assertTrue(runtime.status.value.needsForegroundService)
        // The ongoing notification mirrors the bubble during the cancel chain.
        val ongoing = shadowOf(notificationManager).getNotification(RideSessionService.ONGOING_NOTIFICATION_ID)
        assertEquals(listOf("I cancelled it", "Show me", "Stop"), ongoing.actions.map { it.title.toString() })
        assertTrue(ongoing.flags and Notification.FLAG_ONGOING_EVENT != 0)

        runtime.requestStop()
        idle()
        assertFalse(shadowOf(service).isStoppedBySelf)

        post(Provider.UBER, "u2", GuardianTestRig.UBER_CANCELLED, ongoing = false)
        assertFalse(runtime.status.value.sessionOpen)
        assertTrue(shadowOf(service).isStoppedBySelf)

        destroy(controller)
        assertFalse(app.graph.sessionActivity.active.value)
    }

    @Test
    fun `while the service runs its tick drives reminders`() {
        runtime.start()
        runtime.startSession(spec(Provider.UBER, Provider.RAPIDO))
        idle()
        val controller = startService()
        bookAndKeepRapido()
        assertTrue(runBlocking { app.graph.outboxRepository.pending() }.any { it.kind == OutboxKind.REMINDER })

        app.fakes.clock.advance(30_000)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        idle()
        assertEquals(1, runtime.uiState.value.remindersSent)
        destroy(controller)
    }

    @Test
    fun `with no session the service stops itself`() {
        runtime.start()
        idle()
        val controller = startService()
        idle()
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `fare capture adds the mediaProjection type only while it runs`() {
        runtime.start()
        runtime.startSession(spec(Provider.OLA))
        idle()
        assertFalse(runtime.captureStarted()) // not running yet: it starts the service instead
        val service = startService().get()

        assertTrue(runtime.captureStarted())
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            service.foregroundServiceType,
        )
        runtime.captureEnded()
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, RideSessionService.foregroundType(capturing = false))
    }

    @Test
    fun `a notification button reaches the guardian as a user tap`() {
        runtime.start()
        runtime.startSession(spec(Provider.UBER, Provider.RAPIDO))
        idle()
        val rapido = runtime.legIdFor(Provider.RAPIDO)!!

        // What the notification's PendingIntent delivers to the (non-exported) receiver.
        val receiver = NotificationActionReceiver()
        receiver.onReceive(context, GuardianIntents.tapIntent(context, "ongoing", 1, SessionOp(SessionOpKind.BOOKED_HERE, rapido)))
        eventually("Booked here") { runtime.uiState.value.leg(rapido)?.state == RideState.BOOK_REQUESTED }

        receiver.onReceive(context, GuardianIntents.tapIntent(context, "leg", 0, GuardianTap(TapScope.LEG, rapido, UiAction.DISMISS)))
        eventually("Dismiss") { runtime.uiState.value.leg(rapido)?.dismissed == true }
        assertEquals(RideState.SELECTED, runtime.uiState.value.leg(runtime.legIdFor(Provider.UBER))!!.state)

        // Intents that are not guardian taps are ignored.
        receiver.onReceive(context, Intent("app.ridemate.SOMETHING_ELSE"))
        assertEquals(RideState.SELECTED, runtime.uiState.value.leg(runtime.legIdFor(Provider.UBER))!!.state)
    }

    @Test
    fun `the notification PendingIntent targets the receiver with the encoded button`() {
        val pending = GuardianIntents.tapPendingIntent(context, "card", 2, GuardianTap(TapScope.PROMPT, "p1", UiAction.NOT_YET))
        val saved = shadowOf(pending).savedIntent
        assertEquals(NotificationActionReceiver::class.java.name, saved.component?.className)
        assertEquals(GuardianTap(TapScope.PROMPT, "p1", UiAction.NOT_YET), GuardianIntents.target(saved))
    }

    @Test
    fun `Read fares on a notification opens RideMate directly instead of going through the receiver`() {
        val pending = GuardianIntents.buttonPendingIntent(context, "ongoing", 0, SessionOp(SessionOpKind.READ_FARES))
        assertTrue(shadowOf(pending).isActivityIntent)
        assertEquals(GuardianIntents.DESTINATION_READ_FARES, shadowOf(pending).savedIntent.getStringExtra(GuardianIntents.EXTRA_DESTINATION))
        assertTrue(shadowOf(GuardianIntents.buttonPendingIntent(context, "c", 0, SessionOp(SessionOpKind.STOP))).isBroadcastIntent)
    }

    @Test
    fun `after a reboot the active session is restored and the service restarted`() {
        // The process before the reboot, writing to the same database.
        val before = GuardianTestRig(db = app.graph.db, clock = app.fakes.clock).start()
        before.firstDriverChain()
        before.kill()
        app.fakes.clock.wall += 2 * 60_000L
        app.fakes.clock.elapsed = 5_000L
        app.fakes.bootIds.id = "boot-2"

        context.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setClass(context, BootReceiver::class.java))
        eventually("restore") { runtime.status.value.restored }

        val status = runtime.status.value
        assertTrue(status.sessionOpen)
        assertTrue(status.needsForegroundService)
        assertEquals(1, status.pendingExtras)
        assertEquals(RideSessionService::class.java.name, shadowOf(context as Application).nextStartedService.component?.className)
        // The overdue reminders collapsed into one on restore; the next one is on this boot's clock.
        assertTrue(runtime.uiState.value.remindersSent >= 1)
        val reminder = runBlocking { app.graph.outboxRepository.pending() }.single { it.kind == OutboxKind.REMINDER }
        assertTrue(reminder.dueElapsedMs > app.fakes.clock.elapsed)
        assertNotNull(runtime.legIdFor(Provider.UBER))
    }

    @Test
    fun `other broadcasts are ignored by the boot receiver`() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_TIME_CHANGED))
        assertFalse(runtime.status.value.restored)
    }
}
