package app.ridemate.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.config.ConfigInfo
import app.ridemate.config.FetchStatus
import app.ridemate.update.UpdateMessages
import app.ridemate.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PlatformMessagesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun summary(
        kind: SignInKind = SignInKind.GOOGLE,
        config: ConfigInfo = ConfigInfo(1_793_174_405_000L, FetchStatus.SUCCESS, "abc123def456"),
        pendingErase: Boolean = false,
    ) = MyDataSummary(
        counts = HeldData.entries.associateWith { it.ordinal },
        signInKind = kind,
        signInEmail = if (kind == SignInKind.GOOGLE) "rider@example.com" else null,
        lastSessionAtUtc = 1_793_174_405_000L,
        remoteConfig = config,
        pendingServerErase = pendingErase,
    )

    @Test
    fun `my data lists every table, the account, last session and settings version`() {
        val lines = PrivacyMessages.myDataLines(context, summary(pendingErase = true), Locale.ENGLISH)
        assertEquals("Ride comparisons: 0", lines.first())
        assertTrue(lines.contains("Developer recorder entries: 8"))
        assertTrue(lines.contains("Signed in with Google as rider@example.com"))
        assertTrue(lines.contains("Last comparison: 28 Oct 2026, 1:30 PM"))
        assertTrue(lines.contains("Settings version abc123def456, fetched 28 Oct 2026, 1:30 PM"))
        assertEquals("Server erase pending", lines.last())
        HeldData.entries.forEach { assertTrue(context.getString(PrivacyMessages.heldDataLabelRes(it)).isNotBlank()) }

        val anonymous = PrivacyMessages.myDataLines(context, summary(SignInKind.ANONYMOUS, ConfigInfo(null, FetchStatus.NEVER, null)))
        assertTrue(anonymous.contains("Manual mode (no Google sign-in)"))
        assertTrue(anonymous.contains("Using built-in settings"))
        assertTrue(PrivacyMessages.myDataLines(context, summary(SignInKind.SIGNED_OUT)).contains("Not signed in"))
    }

    @Test
    fun `erase status wording`() {
        assertNull(PrivacyMessages.eraseStatusText(context, EraseStatus.Idle, "p@example.com"))
        assertNull(PrivacyMessages.eraseStatusText(context, EraseStatus.Running, "p@example.com"))
        assertTrue(PrivacyMessages.eraseStatusText(context, EraseStatus.WaitingForServer, "p@example.com")!!.contains("as soon as"))
        assertTrue(PrivacyMessages.eraseStatusText(context, EraseStatus.NeedsReauth, "p@example.com")!!.contains("Sign in"))
        assertTrue(PrivacyMessages.eraseStatusText(context, EraseStatus.LocalWipeFailed, "p@example.com")!!.contains("Free up some storage"))
        val done = EraseStatus.Finished(EraseResult(ServerErase.DONE, AccountErase.DELETED, true))
        assertEquals("Your RideMate data and account were erased.", PrivacyMessages.eraseStatusText(context, done, "p@example.com"))
        val failed = EraseStatus.Finished(EraseResult(ServerErase.FAILED, AccountErase.DELETED, true))
        assertTrue(PrivacyMessages.eraseStatusText(context, failed, "p@example.com")!!.contains("p@example.com"))
    }

    @Test
    fun `update wording`() {
        assertNull(UpdateMessages.headline(context, UpdateState()))
        val available = UpdateState(available = true, versionCode = 5, versionName = "1.1.0", url = "https://x", sha256 = "ab".repeat(32))
        assertEquals("Update available: version 1.1.0", UpdateMessages.headline(context, available))
        assertEquals("SHA-256: ${"ab".repeat(32)}", UpdateMessages.checksum(context, available))
        assertNull(UpdateMessages.checksum(context, UpdateState()))
        assertTrue(UpdateMessages.headline(context, available.copy(belowMinSupported = true, forceUpdate = true))!!.contains("no longer supported"))
    }
}
