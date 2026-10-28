package app.ridemate.launch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.ridemate.RideMateApp
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Provider
import app.ridemate.trip.TripPlace
import kotlinx.coroutines.launch

/**
 * The target of the heads-up "Open Ola" notification ([AndroidLaunchNotifier]). It is started by the user's tap, so
 * it is in the foreground: it opens the ride app through [AppLauncher.openFromUserTap] (which also copies the drop
 * address and records the leg as OPENED) and finishes. Transparent, not exported, never in recents.
 */
class OpenRideAppActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = requestFrom(intent)
        // A re-created instance (e.g. rotated mid-launch) must not open the app a second time.
        if (request == null || savedInstanceState != null) {
            finish()
            return
        }
        val graph = RideMateApp.graph(this)
        lifecycleScope.launch {
            val result = graph.appLauncher.openFromUserTap(request)
            // What happened and what to do next: "drop copied, paste it", "not installed", "couldn't open", …
            LaunchMessages.resultText(this@OpenRideAppActivity, result)?.let { text ->
                Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    companion object {
        private const val EXTRA_PROVIDER = "app.ridemate.launch.PROVIDER"
        private const val EXTRA_LEG_ID = "app.ridemate.launch.LEG_ID"
        private const val EXTRA_PICKUP = "app.ridemate.launch.PICKUP"
        private const val EXTRA_DROP = "app.ridemate.launch.DROP"
        private const val LABEL = "label"
        private const val LAT = "lat"
        private const val LNG = "lng"

        /** The explicit intent the heads-up notification opens (only RideMate can start it: not exported). */
        fun intent(context: Context, request: LaunchRequest): Intent = Intent(context, OpenRideAppActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_PROVIDER, request.provider.wireId)
            .putExtra(EXTRA_LEG_ID, request.legId)
            .putExtra(EXTRA_PICKUP, bundleOf(request.pickup))
            .putExtra(EXTRA_DROP, bundleOf(request.drop))

        /** Reads a request back; null when anything is missing or malformed. */
        fun requestFrom(intent: Intent?): LaunchRequest? {
            val extras = intent?.extras ?: return null
            val provider = extras.getString(EXTRA_PROVIDER)?.let(Provider::fromWireId) ?: return null
            if (provider !in Provider.LIVE_APPS) return null
            val pickup = placeFrom(extras.getBundle(EXTRA_PICKUP)) ?: return null
            val drop = placeFrom(extras.getBundle(EXTRA_DROP)) ?: return null
            return LaunchRequest(provider, pickup, drop, extras.getString(EXTRA_LEG_ID))
        }

        private fun bundleOf(place: TripPlace): Bundle = Bundle().apply {
            putString(LABEL, place.label)
            place.point?.let {
                putDouble(LAT, it.lat)
                putDouble(LNG, it.lng)
            }
        }

        private fun placeFrom(bundle: Bundle?): TripPlace? {
            val label = bundle?.getString(LABEL)?.takeIf { it.isNotBlank() } ?: return null
            val point = if (bundle.containsKey(LAT) && bundle.containsKey(LNG)) LatLng(bundle.getDouble(LAT), bundle.getDouble(LNG)) else null
            return TripPlace(label, point)
        }
    }
}
