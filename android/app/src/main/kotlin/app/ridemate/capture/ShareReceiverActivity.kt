package app.ridemate.capture

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.ridemate.R
import app.ridemate.RideMateApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receives a screenshot the user shares to RideMate (`ACTION_SEND` of an image): the fallback when a ride app blocks
 * screen capture. The share itself is the user's tap (I6 counts from the moment it arrives). The image is read in
 * memory and handed to the fare reader for the comparison's current app; nothing is saved.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        val graph = RideMateApp.graph(this)
        val receivedAt = graph.clock.elapsedRealtimeMs()
        val uri = SharedScreenshot.streamUri(intent)
        if (uri == null) {
            Toast.makeText(this, R.string.capture_share_not_image, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        lifecycleScope.launch {
            val frame = withContext(Dispatchers.IO) { SharedScreenshot.load(contentResolver, uri, receivedAt) }
            val message = if (frame == null) {
                getString(R.string.capture_share_unreadable)
            } else {
                graph.fareReading.coordinator.onSharedScreenshot(frame)
            }
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
