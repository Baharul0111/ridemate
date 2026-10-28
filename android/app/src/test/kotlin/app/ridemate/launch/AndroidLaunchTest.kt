package app.ridemate.launch

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass
import app.ridemate.session.SessionSpec
import app.ridemate.testing.TestData
import app.ridemate.testing.TestRideMateApp
import app.ridemate.trip.TripPlace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/** The Android-facing launch pieces with real framework objects (Robolectric). */
@RunWith(AndroidJUnit4::class)
class AndroidLaunchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as TestRideMateApp
    private val pickup = TripPlace("Barpeta Road", LatLng(26.5032, 90.9757))
    private val drop = TripPlace("LGBI Airport, Guwahati", LatLng(26.1061, 91.5859))

    private fun installRideApp(provider: Provider, handlesUberLinks: Boolean = false) {
        val pm = shadowOf(context.packageManager)
        pm.installPackage(
            PackageInfo().apply {
                packageName = provider.packageName
                applicationInfo = ApplicationInfo().apply { packageName = provider.packageName }
            },
        )
        val main = ComponentName(provider.packageName, "${provider.packageName}.MainActivity")
        pm.addActivityIfNotPresent(main)
        pm.addIntentFilterForActivity(
            main,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
        if (handlesUberLinks) {
            pm.addIntentFilterForActivity(
                main,
                IntentFilter(Intent.ACTION_VIEW).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addDataScheme("https")
                    addDataAuthority("m.uber.com", null)
                },
            )
        }
    }

    @Test
    fun `the drop clip is labelled RideMate drop and marked not sensitive`() {
        assertTrue(AndroidDropClipboard(context).copyDrop("LGBI Airport, Guwahati"))
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("RideMate drop", clip.description.label)
        assertEquals("LGBI Airport, Guwahati", clip.getItemAt(0).text.toString())
        val extras = clip.description.extras!!
        assertTrue(extras.containsKey("android.content.extra.IS_SENSITIVE"))
        assertFalse(extras.getBoolean("android.content.extra.IS_SENSITIVE", true))
    }

    @Test
    fun `installed ride apps are found through their launcher activity`() {
        installRideApp(Provider.OLA)
        val apps = PackageManagerApps(context)
        assertTrue(apps.isInstalled(Provider.OLA))
        assertFalse(apps.isInstalled(Provider.UBER))
        assertFalse(apps.isInstalled(Provider.DEMO_PEPPER))
        assertFalse(apps.isInstalled(Provider.ONDC))
        assertEquals(listOf(Provider.OLA), apps.installedRideApps())
        assertEquals("com.olacabs.customer", apps.launchIntent(Provider.OLA)!!.`package`)
        assertNull(apps.launchIntent(Provider.RAPIDO))
    }

    @Test
    fun `the uber link resolves only when uber handles it`() {
        val link = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://m.uber.com/ul/?action=setPickup"))
            .setPackage(Provider.UBER.packageName)
            .addCategory(Intent.CATEGORY_BROWSABLE)
        installRideApp(Provider.UBER, handlesUberLinks = false)
        assertFalse(PackageManagerApps(context).canHandle(link))
        installRideApp(Provider.UBER, handlesUberLinks = true)
        assertTrue(PackageManagerApps(context).canHandle(link))
    }

    @Test
    fun `the activity starter adds NEW_TASK`() {
        val intent = Intent(Intent.ACTION_MAIN).setPackage(Provider.OLA.packageName)
        assertTrue(ContextActivityStarter(context).start(intent))
        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Provider.OLA.packageName, started.`package`)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `the heads-up notification opens the launch activity for that trip`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val request = LaunchRequest(Provider.RAPIDO, pickup, drop, "leg-1")
        assertTrue(AndroidLaunchNotifier(context).postOpenApp(request))

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(AndroidLaunchNotifier.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        val notification = shadowOf(manager).getNotification(AndroidLaunchNotifier.notificationId(Provider.RAPIDO))
        assertNotNull(notification)
        assertEquals("Open Rapido", notification.extras.getString("android.title"))
        val tapIntent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(OpenRideAppActivity::class.java.name, tapIntent.component!!.className)
        assertEquals(request, OpenRideAppActivity.requestFrom(tapIntent))
        assertEquals(1, notification.actions.size)

        AndroidLaunchNotifier(context).cancel(Provider.RAPIDO)
        assertNull(shadowOf(manager).getNotification(AndroidLaunchNotifier.notificationId(Provider.RAPIDO)))
    }

    @Test
    fun `no heads-up when notifications are off`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertFalse(AndroidLaunchNotifier(context).postOpenApp(LaunchRequest(Provider.OLA, pickup, drop)))
    }

    @Test
    fun `no heads-up when the user switched off the open-app channel`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            android.app.NotificationChannel(AndroidLaunchNotifier.CHANNEL_ID, "Open ride apps", NotificationManager.IMPORTANCE_NONE),
        )
        assertFalse(AndroidLaunchNotifier(context).postOpenApp(LaunchRequest(Provider.OLA, pickup, drop)))
        assertNull(shadowOf(manager).getNotification(AndroidLaunchNotifier.notificationId(Provider.OLA)))
    }

    @Test
    fun `no heads-up without the notification permission`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(AndroidLaunchNotifier(context).postOpenApp(LaunchRequest(Provider.OLA, pickup, drop)))
    }

    @Test
    fun `launch requests survive the intent round trip`() {
        val withPoints = LaunchRequest(Provider.UBER, pickup, drop, "leg-9")
        assertEquals(withPoints, OpenRideAppActivity.requestFrom(OpenRideAppActivity.intent(context, withPoints)))
        val typed = LaunchRequest(Provider.OLA, TripPlace("Hostel 3", null), TripPlace("Fancy Bazaar", null), null)
        assertEquals(typed, OpenRideAppActivity.requestFrom(OpenRideAppActivity.intent(context, typed)))
        assertNull(OpenRideAppActivity.requestFrom(Intent()))
        assertNull(OpenRideAppActivity.requestFrom(null))
        val demo = OpenRideAppActivity.intent(context, withPoints).putExtra("app.ridemate.launch.PROVIDER", "demo_kestrel")
        assertNull(OpenRideAppActivity.requestFrom(demo))
    }

    @Test
    fun `tapping the heads-up opens the app, copies the drop and finishes`() {
        installRideApp(Provider.OLA)
        val request = LaunchRequest(Provider.OLA, pickup, drop, null)
        val controller = Robolectric.buildActivity(OpenRideAppActivity::class.java, OpenRideAppActivity.intent(context, request)).setup()
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(Provider.OLA.packageName, started.`package`)
        assertEquals("LGBI Airport, Guwahati", context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text)
        assertEquals(Provider.OLA, app.graph.appLauncher.pasteDrop.value?.provider)
        assertTrue(controller.get().isFinishing)
        // It tells the user what to do next in Ola: paste the drop.
        assertEquals(
            "Your drop address is copied. In Ola, long-press the drop box and tap Paste.",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `tapping the heads-up for an app that was uninstalled says so instead of a generic failure`() {
        val request = LaunchRequest(Provider.RAPIDO, pickup, drop, null)
        val controller = Robolectric.buildActivity(OpenRideAppActivity::class.java, OpenRideAppActivity.intent(context, request)).setup()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowOf(context as Application).nextStartedActivity)
        assertEquals(
            "Rapido isn't installed on this phone. Pick another app, or try the demo.",
            ShadowToast.getTextOfLatestToast(),
        )
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `a re-created launch activity does not open the app a second time`() {
        installRideApp(Provider.OLA)
        val request = LaunchRequest(Provider.OLA, pickup, drop, null)
        val controller = Robolectric.buildActivity(OpenRideAppActivity::class.java, OpenRideAppActivity.intent(context, request))
        controller.create(android.os.Bundle())
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(shadowOf(context as Application).nextStartedActivity)
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `a malformed launch intent just finishes`() {
        val controller = Robolectric.buildActivity(OpenRideAppActivity::class.java, Intent(context, OpenRideAppActivity::class.java)).setup()
        assertTrue(controller.get().isFinishing)
        assertNull(shadowOf(context as Application).nextStartedActivity)
    }

    @Test
    fun `the graph launcher honours the bound bubble and the guardian records OPENED`() = runBlocking {
        installRideApp(Provider.RAPIDO)
        val graph = app.graph
        val runtime = graph.guardianRuntime
        runtime.startSession(
            SessionSpec(
                pickup.toPlaceOrNull()!!, drop.toPlaceOrNull()!!, listOf(Provider.RAPIDO), listOf(VehicleClass.AUTO),
                sessionId = "s1",
            ),
        )
        runtime.awaitIdle()
        val legId = runtime.legIdFor(Provider.RAPIDO)!!
        graph.bubbleVisibility = object : BubbleVisibility {
            override val isShowing: Boolean = true
        }

        val result = graph.appLauncher.open(LaunchRequest(Provider.RAPIDO, pickup, drop, legId))
        runtime.awaitIdle()

        assertEquals(LaunchOutcome.OPENED, result.outcome)
        assertEquals(RideState.OPENED, runtime.uiState.value.leg(legId)!!.state)
        assertEquals(RideState.OPENED, graph.db.legDao().get(legId)!!.state)
        assertEquals(Provider.RAPIDO.packageName, shadowOf(context as Application).nextStartedActivity.`package`)
    }
}
