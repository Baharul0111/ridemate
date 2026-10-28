package app.ridemate.integration

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The manifest audit of README "Auditing the manifest" (goal: RideMate never acts inside other apps): no
 * AccessibilityService, no BIND_ACCESSIBILITY_SERVICE, no input-injection or shell permission, backups off, and only
 * the intended components exported. Checked on the merged manifest the app runs with (all libraries included) and on
 * the app's own source manifest (every component declared exactly once).
 */
@RunWith(AndroidJUnit4::class)
class ManifestAuditTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Suppress("DEPRECATION")
    private val info by lazy {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS,
        )
    }

    private val components: List<ComponentInfo> by lazy {
        info.activities.orEmpty().toList() + info.services.orEmpty() + info.receivers.orEmpty() + info.providers.orEmpty()
    }

    @Test
    fun `no accessibility service, no input injection, no shell or overlay-of-others powers`() {
        val requested = info.requestedPermissions.orEmpty().toSet()
        val forbidden = setOf(
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.permission.INJECT_EVENTS",
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.READ_LOGS",
            "android.permission.DUMP",
            "android.permission.CAPTURE_VIDEO_OUTPUT",
            "android.permission.CAPTURE_SECURE_VIDEO_OUTPUT",
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.SCHEDULE_EXACT_ALARM",
        )
        assertEquals("forbidden permissions requested", emptySet<String>(), requested intersect forbidden)
        info.services.orEmpty().forEach { service ->
            assertFalse("${service.name} is an AccessibilityService", service.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE")
        }
        // The one listener reads notifications only; it is bound by the system with its permission.
        val listener = info.services.orEmpty().single { it.name == "app.ridemate.watch.RideWatchService" }
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", listener.permission)
    }

    @Test
    fun `backups are off`() {
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun `only the intended components are exported, and every library export is permission-protected`() {
        val ownExported = components.filter { it.exported && it.name.startsWith("app.ridemate.") }.map { it.name }.toSet()
        assertEquals(
            setOf(
                "app.ridemate.MainActivity", // the launcher
                "app.ridemate.capture.ShareReceiverActivity", // share a screenshot (FLAG_SECURE fallback)
                "app.ridemate.watch.RideWatchService", // bound only by the system (BIND_NOTIFICATION_LISTENER_SERVICE)
            ),
            ownExported,
        )
        // Debug builds only: Compose tooling's preview host and the UI-test manifest's empty activity.
        val debugOnly = setOf("androidx.compose.ui.tooling.PreviewActivity", "androidx.activity.ComponentActivity")
        components.filter { it.exported && !it.name.startsWith("app.ridemate.") && it.name !in debugOnly }.forEach { c ->
            val permission = when (c) {
                is android.content.pm.ActivityInfo -> c.permission
                is android.content.pm.ServiceInfo -> c.permission
                is android.content.pm.ProviderInfo -> c.readPermission ?: c.writePermission
                else -> null
            }
            assertTrue("library component ${c.name} is exported without a permission", !permission.isNullOrBlank())
        }
    }

    @Test
    fun `Firebase Auth's exported web-redirect activities are removed`() {
        val names = components.map { it.name }.toSet()
        assertFalse("com.google.firebase.auth.internal.GenericIdpActivity" in names)
        assertFalse("com.google.firebase.auth.internal.RecaptchaActivity" in names)
    }

    @Test
    fun `every component of the app's own manifest is declared exactly once`() {
        val manifest = sequenceOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml", "android/app/src/main/AndroidManifest.xml")
            .map(::File).first { it.isFile }.readText()
        val names = Regex("""<(activity|service|receiver|provider)\s[^>]*?android:name="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest).map { it.groupValues[2] }.filter { it.startsWith(".") }.toList() // RideMate's own
        assertTrue(names.isNotEmpty())
        assertEquals("duplicate declarations", names.distinct(), names)
        // Every app component class exists (a typo would only show up at run time).
        names.filter { it.startsWith(".") }.forEach { Class.forName("app.ridemate$it") }
        assertFalse("no accessibility service in the source manifest", "android.accessibilityservice" in manifest)
        assertTrue("allowBackup is false", """android:allowBackup="false"""" in manifest)
    }
}
