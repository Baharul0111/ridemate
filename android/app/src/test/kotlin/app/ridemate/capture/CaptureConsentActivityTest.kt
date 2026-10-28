package app.ridemate.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.testing.TestRideMateApp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@OptIn(DelicateCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CaptureConsentActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val capture = (context as TestRideMateApp).graph.fareReading.capture

    private fun consentRound(resultCode: Int, data: Intent?): Pair<ConsentResult, Activity> {
        val answer = GlobalScope.async(Dispatchers.Unconfined) { capture.obtainConsent(CapturePurpose.FARE_READING, timeoutMs = 5_000) }
        // CaptureManager asked Android to show the transparent consent host.
        val hostIntent = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(CaptureConsentActivity::class.java.name, hostIntent.component!!.className)
        assertTrue(hostIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        val activity = Robolectric.buildActivity(CaptureConsentActivity::class.java, hostIntent).setup().get()
        val systemDialog = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("the system consent dialog was requested", systemDialog)
        shadowOf(activity).receiveResult(systemDialog.intent, resultCode, data)
        shadowOf(Looper.getMainLooper()).idle()
        return runBlocking { answer.await() } to activity
    }

    @Test
    fun `refusing Android's dialog is reported and the host closes`() {
        val (result, activity) = consentRound(Activity.RESULT_CANCELED, null)

        assertEquals(ConsentResult.Denied, result)
        assertTrue(activity.isFinishing)
        assertFalse(capture.isActive)
    }

    @Test
    fun `consent without the session service never starts a projection`() {
        val (result, activity) = consentRound(Activity.RESULT_OK, Intent("token"))

        assertEquals(ConsentResult.Failed, result)
        assertTrue(activity.isFinishing)
        assertFalse(capture.isActive)
        assertEquals(CaptureState.Idle, capture.state.value)
    }
}
