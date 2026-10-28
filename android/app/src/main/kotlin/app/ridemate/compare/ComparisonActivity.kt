package app.ridemate.compare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import app.ridemate.R
import app.ridemate.RideMateApp
import app.ridemate.bubble.TapIds
import app.ridemate.core.model.Provider
import app.ridemate.core.model.VehicleClass
import app.ridemate.ui.RideMateTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Hosts [ComparisonScreen] for the active comparison (or [EXTRA_SESSION_ID]); opened from the bubble in manual mode
 * with the price form ([EXTRA_MANUAL_PROVIDER]). Not exported.
 */
class ComparisonActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val module = RideMateApp.graph(this).fareReading
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val manualProvider = intent.getStringExtra(EXTRA_MANUAL_PROVIDER)?.let(Provider::fromWireId)
        setContent {
            RideMateTheme {
                Surface(Modifier.fillMaxSize()) {
                    ComparisonRoute(module, sessionId, manualProvider, Modifier.safeDrawingPadding())
                }
            }
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "app.ridemate.compare.extra.SESSION_ID"
        const val EXTRA_MANUAL_PROVIDER = "app.ridemate.compare.extra.MANUAL_PROVIDER"

        fun intent(context: Context, sessionId: String?, manualFor: Provider? = null): Intent =
            Intent(context, ComparisonActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_MANUAL_PROVIDER, manualFor?.wireId)
    }
}

/**
 * [ComparisonScreen] wired to the fare-reading module: live rows from Room, a 1 s ticker for freshness, the manual
 * prompt from the latest tap, Book / Booked here / refresh / typed prices.
 */
@Composable
fun ComparisonRoute(module: FareReadingModule, sessionId: String?, manualProvider: Provider?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val activeId by remember(sessionId) {
        if (sessionId != null) flowOf(sessionId) else module.comparisons.observeActiveSessionId()
    }.collectAsState(initial = sessionId)
    val id = activeId
    val data by remember(id) {
        if (id == null) flowOf(ComparisonData.empty("")) else module.comparisons.observe(id)
    }.collectAsState(initial = ComparisonData.empty(id.orEmpty()))
    val lastRead by module.coordinator.lastRead.collectAsState()
    val now = rememberWallClock(module.clock)
    val manual = remember(lastRead, manualProvider) { manualPromptFor(context, lastRead, manualProvider) }
    val bookedText = { app: String -> resources.getString(R.string.compare_booked_done, app) }
    val callbacks = remember(module, data) {
        ComparisonCallbacks(
            onBook = { provider -> module.appLauncher.open(provider) },
            onBookedHere = module.sessionCommands?.let { commands ->
                { provider: Provider ->
                    scope.launch {
                        val ok = commands.bookedHere(provider, TapIds.newTapId())
                        val app = FareReadingMessages.name(context, provider)
                        android.widget.Toast.makeText(
                            context,
                            if (ok) bookedText(app) else resources.getString(R.string.compare_booked_failed, app),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    Unit
                }
            },
            onRefresh = { provider -> module.appLauncher.open(provider) },
            onSubmitManual = { provider: Provider, vehicleClass: VehicleClass, text: String, done: (String?) -> Unit ->
                val leg = data.legFor(provider)
                if (leg == null) {
                    done(resources.getString(R.string.compare_manual_no_leg))
                } else {
                    scope.launch {
                        val result = module.manualEntry.enter(leg.id, provider, vehicleClass, text)
                        done(manualEntryError(context, result))
                    }
                }
            },
        )
    }
    ComparisonScreen(data = data, nowMs = now, manual = manual, callbacks = callbacks, modifier = modifier)
}

/** The form's prompt: the latest manual-mode tap, or an explicit request to type a price for [manualProvider]. */
fun manualPromptFor(context: Context, lastRead: LastRead?, manualProvider: Provider?): ManualPrompt? {
    val manual = lastRead?.result as? ReadFaresResult.ManualMode
    return when {
        manual != null && (manualProvider == null || manual.provider == manualProvider) ->
            ManualPrompt(FareReadingMessages.manualText(context, manual), manual.provider, manual.suggestedClasses)
        manualProvider != null -> ManualPrompt(null, manualProvider)
        else -> null
    }
}

/** Null when saved, else what to do next. */
fun manualEntryError(context: Context, result: ManualEntryResult): String? = when (result) {
    is ManualEntryResult.Saved -> null
    ManualEntryResult.NotAPrice -> context.getString(R.string.compare_manual_not_a_price)
    is ManualEntryResult.OutOfRange -> context.getString(
        R.string.compare_manual_out_of_range,
        app.ridemate.core.format.IndianFormat.rupees(result.minInr.toLong()),
        app.ridemate.core.format.IndianFormat.rupees(result.maxInr.toLong()),
    )
    ManualEntryResult.NoSuchLeg -> context.getString(R.string.compare_manual_no_leg)
}
