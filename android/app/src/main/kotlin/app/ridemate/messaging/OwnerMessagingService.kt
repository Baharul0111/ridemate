package app.ridemate.messaging

import app.ridemate.RideMateApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

/**
 * FCM for the owner only: alert pushes from the server's dispatcher (docs/slo-and-alerts.md) and the owner's device
 * token registration (`POST /v1/owner/fcm-token`). Regular users never get pushes; their token is never sent anywhere.
 * FCM calls both handlers on a background thread, so they finish their work before returning.
 */
class OwnerMessagingService : FirebaseMessagingService() {

    private val messaging: OwnerMessaging get() = RideMateApp.graph(this).ownerMessaging

    override fun onNewToken(token: String) {
        runBlocking { messaging.onNewToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification
        messaging.onMessage(message.data, notification?.title, notification?.body)
    }
}
