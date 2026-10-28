package app.ridemate

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.ridemate.settings.LegacyLocale
import app.ridemate.ui.DeepLink
import app.ridemate.ui.DeepLinks
import app.ridemate.ui.RideMateRoot
import app.ridemate.ui.RideMateTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The single activity: onboarding, Home, Settings, developer mode and (through `featureBindings`) the other areas'
 * screens. Notification taps arrive as [DeepLinks] intents, here or in [onNewIntent] (singleTask).
 */
class MainActivity : ComponentActivity() {
    private val deepLinks = MutableStateFlow<DeepLink?>(null)

    override fun attachBaseContext(newBase: Context) {
        // Android 10–12: the in-app language; Android 13+ uses the system per-app locale.
        super.attachBaseContext(LegacyLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) deepLinks.value = DeepLinks.parse(intent)
        val graph = RideMateApp.graph(this)
        setContent {
            RideMateTheme {
                RideMateRoot(graph, deepLinks, onDeepLinkHandled = { deepLinks.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLinks.parse(intent)?.let { deepLinks.value = it }
    }
}
