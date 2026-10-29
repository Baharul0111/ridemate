package app.ridemate.onboarding

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.ui.SystemIntents
import app.ridemate.watch.RideWatchService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Onboarding's real Android wiring (goal 20): which settings screen each step opens, the sideloaded check that gates
 * the "Allow restricted settings" step, and the OEM screen falling back to App info when the maker's is missing.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as Application
    private val packageUri = Uri.fromParts("package", context.packageName, null)

    @Test
    fun `notification access opens RideMate's own listener page, then the list`() {
        val intents = PermissionIntents.notificationAccess(context)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, intents[0].action)
        assertEquals(
            ComponentName(context, RideWatchService::class.java).flattenToString(),
            intents[0].getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME),
        )
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, intents.last().action)
    }

    @Test
    fun `overlay, battery and App info open RideMate's own pages`() {
        val overlay = PermissionIntents.overlay(context).first()
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, overlay.action)
        assertEquals(packageUri, overlay.data)
        val battery = PermissionIntents.batteryExemption(context).first()
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, battery.action)
        assertEquals(packageUri, battery.data)
        val info = PermissionIntents.appInfo(context).single()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, info.action)
        assertEquals(packageUri, info.data)
        val notifications = PermissionIntents.appNotificationSettings(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notifications.first().action)
        assertEquals(context.packageName, notifications.first().getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals("App info is the last resort", Settings.ACTION_APPLICATION_DETAILS_SETTINGS, notifications.last().action)
    }

    @Test
    fun `an app installed from outside a store is sideloaded, a Play install is not`() {
        val pm = shadowOf(context.packageManager)
        pm.setInstallSourceInfo(context.packageName, null, null)
        val sideloaded = DeviceProfile.of(context)
        assertTrue(sideloaded.sideloaded)
        assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU, sideloaded.restrictedSettingsPossible)

        pm.setInstallSourceInfo(context.packageName, "com.android.vending", "com.android.vending")
        val store = DeviceProfile.of(context)
        assertFalse(store.sideloaded)
        assertFalse(store.restrictedSettingsPossible)
    }

    @Test
    fun `before Android 13 a sideloaded app has no restricted settings to allow`() {
        assertFalse(DeviceProfile(32, "12", "Google", sideloaded = true).restrictedSettingsPossible)
        assertTrue(DeviceProfile(33, "13", "Google", sideloaded = true).restrictedSettingsPossible)
        assertFalse(DeviceProfile(34, "14", "Google", sideloaded = false).restrictedSettingsPossible)
    }

    @Test
    fun `an OEM button whose screen is missing falls back to App info`() {
        shadowOf(app).checkActivities(true)
        // App info exists on every phone; the Xiaomi autostart screen does not exist here.
        val appInfo = ComponentName("com.android.settings", "com.android.settings.applications.InstalledAppDetails")
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(appInfo)
        pm.addIntentFilterForActivity(
            appInfo,
            IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("package")
            },
        )
        val intents = OemGuidance.settingsIntents(context, OemFamily.XIAOMI)
        assertEquals("com.miui.securitycenter", intents.first().component?.packageName)

        assertTrue(SystemIntents.startFirst(context, *intents.toTypedArray()))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals(packageUri, started.data)

        // A maker with nothing beyond Android's own setting goes straight to App info.
        assertEquals(listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS), OemGuidance.settingsIntents(context, OemFamily.OTHER).map { it.action })
    }
}
