package app.ridemate.watch

import android.app.Notification
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.testing.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class SbnPostReaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val reader = SbnPostReader(VersionCodeLookup { pkg -> if (pkg == RAPIDO) 4_242L else null })

    private fun sbn(notification: Notification, pkg: String = RAPIDO, id: Int = 7, tag: String? = "ride", postTime: Long = 1_793_000_000_000L) =
        StatusBarNotification(pkg, pkg, id, tag, Process.myUid(), 0, 0, notification, Process.myUserHandle(), postTime)

    private fun builder() = NotificationCompat.Builder(context, "ride_status").setSmallIcon(android.R.drawable.ic_dialog_info)

    @Test
    fun `reads title, the expanded big text, structure and the posting app's version`() {
        val notification = builder()
            .setContentTitle("Captain assigned")
            .setContentText("Your captain is on the way")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Your captain Ravi is on the way. 4 min away"))
            .setOngoing(true)
            .setCategory("status")
            .build()

        val post = reader.read(sbn(notification))

        assertEquals(RAPIDO, post.packageName)
        assertEquals("Captain assigned", post.title)
        assertEquals("Your captain Ravi is on the way. 4 min away", post.text)
        assertEquals("ride_status", post.channelId)
        assertEquals("status", post.category)
        assertTrue(post.isOngoing)
        assertEquals(7, post.id)
        assertEquals("ride", post.tag)
        assertEquals(1_793_000_000_000L, post.postTimeMs)
        assertEquals(4_242L, post.versionCode)
        assertTrue(post.sbnKey.contains(RAPIDO))
        assertFalse(post.isRedacted)
    }

    @Test
    fun `falls back to the plain text and to inbox lines`() {
        val plain = reader.read(sbn(builder().setContentTitle("Uber").setContentText("Finding your ride").build()))
        assertEquals("Finding your ride", plain.text)

        val inbox = reader.read(
            sbn(
                builder().setContentTitle("Ola")
                    .setStyle(NotificationCompat.InboxStyle().addLine("Your cab is on the way").addLine("OTP 4719"))
                    .build(),
            ),
        )
        assertEquals("Your cab is on the way\nOTP 4719", inbox.text)
    }

    @Test
    fun `empty and Android-redacted posts are flagged as redacted`() {
        val empty = reader.read(sbn(builder().build()))
        assertTrue(empty.isRedacted)
        assertNull(empty.title)

        val hidden = reader.read(sbn(builder().setContentTitle("Rapido").setContentText("Sensitive notification content hidden").build()))
        assertTrue(hidden.platformRedacted)
        assertTrue(hidden.isRedacted)

        val detector = RedactionDetector()
        assertFalse(detector.isRedacted("Rapido", "Your captain is on the way"))
        assertTrue(detector.isRedacted(null, "  "))
    }

    @Test
    fun `the phone's own placeholder wording counts as redacted in any language`() {
        // What Android puts in place of the content on a phone set to Hindi (the framework string, not ours).
        val hindiPlaceholder = "संवेदनशील सूचना की सामग्री छिपाई गई है।"
        val localized = SbnPostReader(VersionCodeLookup { 4_242L }, RedactionDetector(listOf(hindiPlaceholder)))

        val hidden = localized.read(sbn(builder().setContentTitle("Rapido").setContentText(hindiPlaceholder).build()))
        assertTrue(hidden.platformRedacted)
        // Without that wording the same post would be read as text (a rule miss), so a second redacted assignment
        // could be fingerprinted like the first.
        assertFalse(reader.read(sbn(builder().setContentTitle("Rapido").setContentText(hindiPlaceholder).build())).platformRedacted)
        // English placeholders are always recognised; a real ride update never is.
        assertTrue(localized.read(sbn(builder().setContentTitle("Uber").setContentText("Contents hidden").build())).platformRedacted)
        assertFalse(localized.read(sbn(builder().setContentTitle("Rapido").setContentText("Captain assigned").build())).platformRedacted)
        // Looking the framework strings up never fails, whatever this Android version has.
        RedactionDetector.systemPlaceholders(context.resources).forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun `the installed versionCode comes from the package manager and unknown apps give null`() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = RAPIDO; longVersionCode = 777L })
        val clock = FakeClock()
        val codes = PackageVersionCodes(context.packageManager, clock)

        assertEquals(777L, codes.versionCode(RAPIDO))
        assertNull(codes.versionCode("com.ubercab"))
    }

    private companion object {
        const val RAPIDO = "com.rapido.passenger"
    }
}
