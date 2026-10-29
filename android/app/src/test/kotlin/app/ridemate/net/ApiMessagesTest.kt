package app.ridemate.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.R
import app.ridemate.data.StorageWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ApiMessagesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `ai pause with a resume time names the IST time`() {
        val text = ApiMessages.text(context, ApiResult.AiPaused("ai_paused", "13:30", null), Locale.ENGLISH)
        assertEquals("AI reading resumes at 1:30 PM (IST). You can type the price meanwhile.", text)
    }

    @Test
    fun `every failure has wording and success has none`() {
        assertNull(ApiMessages.text(context, ApiResult.Ok(Unit)))
        assertEquals(R.string.platform_ai_paused, ApiMessages.resFor(ApiResult.AiPaused("ai_paused", "bad", null)))
        assertEquals(R.string.platform_sign_in_needed, ApiMessages.resFor(ApiResult.AuthNeeded))
        assertEquals(R.string.platform_google_required, ApiMessages.resFor(ApiResult.GoogleRequired))
        assertEquals(R.string.platform_rate_limited, ApiMessages.resFor(ApiResult.RateLimited(3)))
        assertEquals(R.string.platform_offline, ApiMessages.resFor(ApiResult.Offline))
        assertEquals(R.string.platform_timeout, ApiMessages.resFor(ApiResult.Timeout))
        assertEquals(R.string.platform_not_configured, ApiMessages.resFor(ApiResult.NotConfigured))
        assertEquals(R.string.platform_too_large, ApiMessages.resFor(ApiResult.Http(413, "too_large")))
        assertEquals(R.string.platform_server_error, ApiMessages.resFor(ApiResult.Http(500)))
        assertEquals(
            "AI reading is paused for now. You can type the price meanwhile.",
            ApiMessages.text(context, ApiResult.AiPaused("ai_unavailable", null, null)),
        )
        assertNull(ApiMessages.storageWarningRes(StorageWarning.NONE))
        assertEquals(R.string.platform_storage_full, ApiMessages.storageWarningRes(StorageWarning.FULL))
        assertEquals(R.string.platform_storage_write_failed, ApiMessages.storageWarningRes(StorageWarning.WRITE_FAILED))
    }

    @Test
    @Config(qualifiers = "hi")
    fun `hindi wording exists`() {
        val text = ApiMessages.text(context, ApiResult.Offline)!!
        assertEquals(context.getString(R.string.platform_offline), text)
        assertEquals(true, text.contains("ऑफ़लाइन"))
    }
}
