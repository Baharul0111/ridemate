package app.ridemate.watch

import android.content.Context
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Place
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass
import app.ridemate.session.AssetGuardianConfig
import app.ridemate.session.SessionSpec
import app.ridemate.testing.TestRideMateApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationListenerService

@RunWith(AndroidJUnit4::class)
class RideWatchServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as TestRideMateApp

    @Before
    fun setUp() {
        ShadowNotificationListenerService.reset()
        app.fakes.bundled = AssetGuardianConfig.bundled(context)
    }

    private fun post(pkg: String, title: String, text: String, key: Int = 1): StatusBarNotification {
        val n = NotificationCompat.Builder(context, "ride").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setOngoing(true).build()
        return StatusBarNotification(pkg, pkg, key, null, Process.myUid(), 0, 0, n, Process.myUserHandle(), app.fakes.clock.wall)
    }

    private fun idle() = runBlocking { withTimeout(20_000) { repeat(2) { app.graph.guardianRuntime.awaitIdle() } } }

    private fun state(provider: Provider): RideState {
        val runtime = app.graph.guardianRuntime
        return runtime.uiState.value.leg(runtime.legIdFor(provider))!!.state
    }

    @Test
    fun `connect re-reads the shade, posts drive the guardian, disconnect asks for a rebind`() {
        val runtime = app.graph.guardianRuntime
        runtime.start()
        runtime.startSession(
            SessionSpec(Place("A", LatLng(26.1, 91.7)), Place("B", LatLng(26.2, 91.8)), listOf(Provider.RAPIDO, Provider.UBER), listOf(VehicleClass.AUTO)),
        )
        idle()
        runtime.bookedHere(Provider.RAPIDO)
        idle()

        val service = Robolectric.buildService(RideWatchService::class.java).create().get()
        val shadow = shadowOf(service)
        shadow.addActiveNotification(post("com.rapido.passenger", "Rapido", "Looking for a captain near you"))
        shadow.addActiveNotification(post("com.whatsapp", "Mom", "Call me when you reach"))

        service.onListenerConnected()
        idle()
        assertTrue(app.graph.rideWatchStatus.connected.value)
        assertEquals(RideState.SEARCHING, state(Provider.RAPIDO))

        service.onNotificationPosted(post("com.rapido.passenger", "Captain assigned", "Your captain Ravi is on the way. 4 min away"))
        idle()
        assertEquals(RideState.ASSIGNED, state(Provider.RAPIDO))

        service.onListenerDisconnected()
        idle()
        assertFalse(app.graph.rideWatchStatus.connected.value)
        assertTrue(ShadowNotificationListenerService.getRebindRequestCount() > 0)
    }

    @Test
    fun `removals with their reason reach the guardian`() {
        val runtime = app.graph.guardianRuntime
        runtime.start()
        runtime.startSession(
            SessionSpec(Place("A", LatLng(26.1, 91.7)), Place("B", LatLng(26.2, 91.8)), listOf(Provider.OLA), listOf(VehicleClass.AUTO)),
        )
        runtime.bookedHere(Provider.OLA)
        idle()
        val service = Robolectric.buildService(RideWatchService::class.java).create().get()
        service.onListenerConnected()
        val searching = post("com.olacabs.customer", "Ola", "Finding a cab near you")
        service.onNotificationPosted(searching)
        idle()
        assertEquals(RideState.SEARCHING, state(Provider.OLA))

        // App-cancel removal of the ongoing post is half of rung 3: recorded, nothing confirmed without the screen.
        service.onNotificationRemoved(searching, null, NotificationListenerService.REASON_APP_CANCEL)
        idle()
        assertEquals(RideState.SEARCHING, state(Provider.OLA))

        // Other apps' posts never reach the guardian.
        service.onNotificationPosted(post("com.whatsapp", "Ola", "Your ride has been cancelled"))
        idle()
        assertEquals(RideState.SEARCHING, state(Provider.OLA))
    }
}
