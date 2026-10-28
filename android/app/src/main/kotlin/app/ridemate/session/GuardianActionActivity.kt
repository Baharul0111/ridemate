package app.ridemate.session

import android.app.Activity
import android.os.Bundle
import app.ridemate.RideMateApp
import app.ridemate.core.guardian.UiAction
import app.ridemate.notify.GuardianIntents
import app.ridemate.notify.GuardianTap

/**
 * Notification buttons that lead to a screen capture (Read fares, Show me, Check screen) or to the comparison (Compare
 * again) arrive here rather than at [NotificationActionReceiver]: starting an activity collapses the notification
 * shade, so the frame shows the ride app and not the shade. Transparent, not exported, no history; it hands the tap to
 * the guardian and finishes at once.
 */
class GuardianActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val target = GuardianIntents.target(intent)
            if (target != null) {
                val graph = RideMateApp.graph(this)
                graph.guardianRuntime.perform(target)
                // Compare again: the comparison opens from here, while RideMate is still allowed to start an activity.
                if ((target as? GuardianTap)?.action == UiAction.COMPARE_AGAIN) {
                    GuardianIntents.rideMateIntent(this, GuardianIntents.DESTINATION_COMPARE, graph.guardianRuntime.status.value.sessionId)
                        ?.let { runCatching { startActivity(it) } }
                }
            }
        }
        finish()
    }
}
