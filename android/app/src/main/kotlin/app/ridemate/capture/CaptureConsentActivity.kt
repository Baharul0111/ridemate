package app.ridemate.capture

import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import app.ridemate.RideMateApp

/**
 * Transparent, non-exported host for Android's "share your screen" consent dialog (MediaProjection). It shows nothing
 * itself: it launches the system dialog, hands the answer to [CaptureManager.onConsentResult] and finishes. The
 * projection token never leaves the process and is used once.
 */
class CaptureConsentActivity : app.ridemate.settings.LocalizedActivity() {

    private val capture: CaptureManager
        get() = RideMateApp.graph(this).fareReading.capture

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        capture.onConsentResult(result.resultCode, result.data)
        finish()
        overrideTransitionNone()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // the pending result is redelivered to the registered callback
        try {
            consent.launch(capture.consentIntent())
        } catch (e: ActivityNotFoundException) {
            capture.onConsentResult(Activity.RESULT_CANCELED, null)
            finish()
        } catch (e: SecurityException) {
            capture.onConsentResult(Activity.RESULT_CANCELED, null)
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun overrideTransitionNone() {
        overridePendingTransition(0, 0)
    }
}
