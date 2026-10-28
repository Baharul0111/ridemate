package app.ridemate.messaging

import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.RideMateApp
import app.ridemate.auth.AuthState
import app.ridemate.testing.ProgrammableApiClient
import app.ridemate.testing.TestRideMateApp
import com.google.firebase.messaging.RemoteMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.util.Locale

/** Owner alert pushes: parsed, worded from resources (en + hi), shown only to the owner. */
@RunWith(AndroidJUnit4::class)
class OwnerAlertsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @org.junit.Before
    fun grantNotifications() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `alert pushes parse and other pushes are ignored`() {
        val alert = OwnerAlert.parse(mapOf("type" to "owner_alert", "alert" to "spend", "value" to "72.5%"), null, null)!!
        assertEquals(OwnerAlertKind.SPEND, alert.kind)
        assertEquals("72.5%", alert.value)
        assertNull("not a number", OwnerAlert.parse(mapOf("type" to "owner_alert", "alert" to "spend", "value" to "lots"), null, null)!!.value)
        assertNull(OwnerAlert.parse(mapOf("type" to "marketing"), "Hi", "Buy"))
        assertNull(OwnerAlert.parse(emptyMap(), null, null))
        // A console test message (notification only) is an alert of unknown kind with its own text.
        val console = OwnerAlert.parse(emptyMap(), " Test ", "Hello owner")!!
        assertNull(console.kind)
        assertEquals("Test", console.pushTitle)
        assertEquals(OwnerAlertKind.KEY_AGE, OwnerAlertKind.fromWire(" KEY_AGE "))
        OwnerAlertKind.entries.forEach { assertEquals(it, OwnerAlertKind.fromWire(it.wire)) }
    }

    @Test
    fun `every alert kind has an English and a Hindi title and a body that names what to do`() {
        val hindi = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("hi")) })
        OwnerAlertKind.entries.forEach { kind ->
            val en = OwnerAlertTexts.of(context, OwnerAlert(kind, null, null, null))
            val hi = OwnerAlertTexts.of(hindi, OwnerAlert(kind, null, null, null))
            assertTrue(en.title, en.title.startsWith("RideMate alert: "))
            assertTrue(hi.title, hi.title.startsWith("RideMate अलर्ट: "))
            assertTrue(en.body.isNotBlank() && hi.body.isNotBlank() && en.body != hi.body)
        }
        val withValue = OwnerAlertTexts.of(context, OwnerAlert(OwnerAlertKind.SERVER_ERRORS, "7%", null, null))
        assertEquals("RideMate alert: server errors above 5%", withValue.title)
        assertTrue(withValue.body.endsWith("Measured: 7%."))
        val generic = OwnerAlertTexts.of(context, OwnerAlert(null, null, null, null))
        assertEquals("RideMate alert: server alert", generic.title)
        val console = OwnerAlertTexts.of(context, OwnerAlert(null, null, "Ping", "From the console"))
        assertEquals("RideMate alert: Ping", console.title)
        assertEquals("From the console", console.body)
    }

    @Test
    fun `the notifier posts on the owner alerts channel`() {
        val notifier = OwnerAlertNotifier(context)
        assertTrue(notifier.show(OwnerAlert(OwnerAlertKind.PROBE_FAILURE, null, null, null)))
        val channel = manager.getNotificationChannel(OwnerAlertNotifier.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(OwnerAlertNotifier.CHANNEL_ID, posted.channelId)
        assertEquals("RideMate alert: daily probe failed", posted.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertNotNull(posted.contentIntent)
        // A repeat of the same kind replaces it rather than stacking.
        notifier.show(OwnerAlert(OwnerAlertKind.PROBE_FAILURE, "2", null, null))
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `notifications off - nothing is posted`() {
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(OwnerAlertNotifier(context).show(OwnerAlert(OwnerAlertKind.TEST, null, null, null)))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `alerts show only while the owner is signed in on this phone`() {
        var auth: AuthState = AuthState.Google("owner@example.com", "Owner", null, "u")
        val owner = OwnerAccess({ auth }, { setOf(OwnerAccess.emailHash("owner@example.com")) })
        val shown = mutableListOf<OwnerAlert>()
        val messaging = OwnerMessaging(OwnerTokenRegistrar(ProgrammableApiClient(), owner) { null }, owner, { shown += it; true })
        assertTrue(messaging.onMessage(mapOf("type" to "owner_alert", "alert" to "slow_reads"), null, null))
        assertFalse("not an alert", messaging.onMessage(mapOf("type" to "other"), null, null))
        auth = AuthState.SignedOut
        assertFalse(messaging.onMessage(mapOf("type" to "owner_alert", "alert" to "slow_reads"), null, null))
        assertEquals(listOf(OwnerAlertKind.SLOW_READS), shown.map { it.kind })
    }

    @Test
    fun `the FCM service delegates to the graph without network for a non-owner`() {
        val app = context as TestRideMateApp
        assertEquals(app.graph, RideMateApp.graph(context))
        val service = Robolectric.buildService(OwnerMessagingService::class.java).create().get()
        service.onNewToken("fcm-token-for-a-rider")
        service.onMessageReceived(RemoteMessage.Builder("owner@fcm").setData(mapOf("type" to "owner_alert", "alert" to "test")).build())
        assertTrue("signed out: nothing shown", shadowOf(manager).allNotifications.isEmpty())
    }
}
