package app.ridemate.precheck

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.verticalScroll
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.R
import app.ridemate.core.model.Provider
import app.ridemate.launch.FakeInstalledApps
import app.ridemate.watch.RideWatchService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowSettings

@RunWith(AndroidJUnit4::class)
class AndroidPrecheckTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val probes = AndroidPrecheckProbes(context, FakeInstalledApps(installed = setOf(Provider.RAPIDO)))

    private fun setOnline(validated: Boolean) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    @Test
    fun `listener probe reads RideMate's notification-access grant`() {
        assertFalse(probes.listenerEnabled())
        val component = ComponentName(context, RideWatchService::class.java)
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", component.flattenToString())
        assertTrue(probes.listenerEnabled())
    }

    @Test
    fun `overlay and battery probes`() {
        ShadowSettings.setCanDrawOverlays(false)
        assertFalse(probes.overlayAllowed())
        ShadowSettings.setCanDrawOverlays(true)
        assertTrue(probes.overlayAllowed())

        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIgnoringBatteryOptimizations(context.packageName, false)
        assertFalse(probes.batteryExempt())
        power.setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(probes.batteryExempt())
    }

    @Test
    fun `notification probe needs both the switch and POST_NOTIFICATIONS`() {
        val app = context as Application
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(probes.notificationsAllowed())
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertFalse(probes.notificationsAllowed())
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(probes.notificationsAllowed())
    }

    @Test
    fun `network probe wants a validated internet connection`() {
        setOnline(validated = true)
        assertTrue(probes.online())
        setOnline(validated = false)
        assertFalse(probes.online())
    }

    @Test
    fun `location and installed apps`() {
        val app = context as Application
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertFalse(probes.locationAllowed())
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(probes.locationAllowed())
        assertEquals(setOf(Provider.RAPIDO), probes.installedRideApps())
    }

    @Test
    fun `every settings fix opens the right screen and in-app fixes have no intent`() {
        val access = FixIntents.forAction(context, FixAction.OPEN_NOTIFICATION_ACCESS)!!
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, access.action)
        assertEquals(
            ComponentName(context, RideWatchService::class.java).flattenToString(),
            access.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME),
        )
        val overlay = FixIntents.forAction(context, FixAction.OPEN_OVERLAY_SETTINGS)!!
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, overlay.action)
        assertEquals("package:${context.packageName}", overlay.dataString)
        assertEquals(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            FixIntents.forAction(context, FixAction.REQUEST_BATTERY_EXEMPTION)!!.action,
        )
        val notifications = FixIntents.forAction(context, FixAction.OPEN_NOTIFICATION_SETTINGS)!!
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notifications.action)
        assertEquals(context.packageName, notifications.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, FixIntents.forAction(context, FixAction.OPEN_INTERNET_SETTINGS)!!.action)
        assertEquals(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            FixIntents.forAction(context, FixAction.REQUEST_LOCATION)!!.action,
        )
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, FixIntents.forAction(context, FixAction.OPEN_APP_INFO)!!.action)
        assertEquals("package:${context.packageName}", FixIntents.forAction(context, FixAction.OPEN_APP_INFO)!!.dataString)
        listOf(FixAction.TRY_DEMO, FixAction.CHOOSE_APPS)
            .forEach { assertNull(it.name, FixIntents.forAction(context, it)) }
        FixAction.entries.mapNotNull { FixIntents.forAction(context, it) }
            .forEach { assertTrue(it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0) }
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, FixIntents.notificationAccessList().action)
    }

    @Test
    fun `every result and fix has English wording`() {
        PrecheckKind.entries.forEach { kind ->
            assertTrue(context.getString(PrecheckMessages.title(kind)).isNotBlank())
            assertTrue(context.getString(PrecheckMessages.body(kind)).isNotBlank())
        }
        FixAction.entries.forEach { assertTrue(context.getString(PrecheckMessages.fixLabel(it)).isNotBlank()) }
        IssueSeverity.entries.forEach { assertTrue(context.getString(PrecheckMessages.severityLabel(it)).isNotBlank()) }
        assertEquals("Turn on notification access", context.getString(R.string.precheck_listener_off_title))
    }

    @Test
    @Config(qualifiers = "hi")
    fun `and Hindi wording`() {
        val hi = RuntimeEnvironment.getApplication()
        PrecheckKind.entries.forEach { kind ->
            assertNotEquals(kind.name, "Turn on notification access", hi.getString(PrecheckMessages.title(kind)))
        }
        assertEquals("सूचना एक्सेस चालू करें", hi.getString(R.string.precheck_listener_off_title))
        assertEquals("डेमो आज़माएँ", hi.getString(PrecheckMessages.fixLabel(FixAction.TRY_DEMO)))
    }

    @Test
    fun `the fix-it card shows the severity in words and calls back with the fix`() {
        val clicked = mutableListOf<FixAction>()
        val issue = PrecheckIssue(PrecheckKind.BATTERY_RESTRICTED, IssueSeverity.WARNING, listOf(FixAction.REQUEST_BATTERY_EXEMPTION))
        compose.setContent { PrecheckCard(issue, onFix = { clicked += it }) }
        compose.onNodeWithText("Warning").assertExists()
        compose.onNodeWithText("Battery saving may delay reminders").assertExists()
        compose.onNodeWithContentDescription("Remove battery limits: Battery saving may delay reminders").assertHasClickAction().performClick()
        assertEquals(listOf(FixAction.REQUEST_BATTERY_EXEMPTION), clicked)
    }

    @Test
    fun `sideloaded on Android 13+, the notification-access and overlay cards explain restricted settings`() {
        val clicked = mutableListOf<FixAction>()
        val listener = PrecheckIssue(PrecheckKind.LISTENER_OFF, IssueSeverity.BLOCKING, listOf(FixAction.OPEN_NOTIFICATION_ACCESS))
        val overlay = PrecheckIssue(PrecheckKind.OVERLAY_OFF, IssueSeverity.WARNING, listOf(FixAction.OPEN_OVERLAY_SETTINGS))
        val battery = PrecheckIssue(PrecheckKind.BATTERY_RESTRICTED, IssueSeverity.WARNING, listOf(FixAction.REQUEST_BATTERY_EXEMPTION))
        compose.setContent {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                PrecheckCard(listener, onFix = { clicked += it }, restrictedSettings = true)
                PrecheckCard(overlay, onFix = { clicked += it }, restrictedSettings = true)
                PrecheckCard(battery, onFix = { clicked += it }, restrictedSettings = true)
            }
        }
        val hint = context.getString(R.string.precheck_restricted_hint)
        compose.onAllNodesWithText(hint).assertCountEquals(2)
        compose.onAllNodesWithText(context.getString(R.string.onb_restricted_steps)).assertCountEquals(2)
        compose.onNodeWithContentDescription("Open App info: Turn on notification access").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Open App info: Floating button is off").performScrollTo().performClick()
        assertEquals(listOf(FixAction.OPEN_APP_INFO, FixAction.OPEN_APP_INFO), clicked)
    }

    @Test
    fun `a store install sees no restricted-settings help`() {
        val listener = PrecheckIssue(PrecheckKind.LISTENER_OFF, IssueSeverity.BLOCKING, listOf(FixAction.OPEN_NOTIFICATION_ACCESS))
        compose.setContent { PrecheckCard(listener, onFix = {}, restrictedSettings = false) }
        compose.onAllNodesWithText(context.getString(R.string.precheck_restricted_hint)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Open App info: Turn on notification access").assertCountEquals(0)
    }

    @Test
    fun `a guardian channel switched off is reported, a missing or quiet one is not`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue("no channel created yet", probes.guardianChannelsAllowed())
        manager.createNotificationChannel(
            android.app.NotificationChannel(app.ridemate.notify.GuardianChannels.PROMPTS, "Questions", NotificationManager.IMPORTANCE_HIGH),
        )
        assertTrue(probes.guardianChannelsAllowed())
        // What Android stores when the user switches the channel off in settings.
        manager.createNotificationChannel(
            android.app.NotificationChannel(app.ridemate.notify.GuardianChannels.ALERTS, "Alerts", NotificationManager.IMPORTANCE_NONE),
        )
        assertFalse(probes.guardianChannelsAllowed())
    }
}
