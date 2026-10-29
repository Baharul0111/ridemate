package app.ridemate.compare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import app.ridemate.R
import app.ridemate.RideMateApp
import app.ridemate.bubble.TapIds
import app.ridemate.core.model.Provider
import app.ridemate.core.model.VehicleClass
import app.ridemate.onboarding.SignInOfferRoute
import app.ridemate.ui.RideMateTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Hosts [ComparisonScreen] for the active comparison (or [EXTRA_SESSION_ID]); opened from the bubble in manual mode
 * with the price form ([EXTRA_MANUAL_PROVIDER]). Not exported.
 */
class ComparisonActivity : app.ridemate.settings.LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = RideMateApp.graph(this)
        val module = graph.fareReading
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val manualProvider = intent.getStringExtra(EXTRA_MANUAL_PROVIDER)?.let(Provider::fromWireId)
        // Google sign-in is offered once more at the first "Read fares" that has to fall back to typing (§4.2
        // onboarding: "offered again at the first Read fares"); either answer leads on to the price form.
        val offerSignIn = savedInstanceState == null && manualProvider != null && graph.appShell.signInReoffer.shouldOfferAtFirstRead()
        setContent {
            RideMateTheme {
                var offering by rememberSaveable { mutableStateOf(offerSignIn) }
                if (offering) {
                    SignInOfferRoute(graph) { offering = false }
                } else {
                    Surface(Modifier.fillMaxSize()) {
                        ComparisonRoute(module, sessionId, manualProvider, Modifier.safeDrawingPadding())
                    }
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
 * The open network's quotes for the list (goals 6–7): NETWORK rows with their seen-at time, grouped with the ride
 * apps' rows by vehicle type. [legId] is the ONDC leg they belong to (rows of another comparison are not shown);
 * [book] gets a row's [GatedRow.rowId]; [refresh] searches the network again (a stale network row's tap).
 */
data class NetworkQuotes(
    val legId: String?,
    val rows: List<app.ridemate.core.gate.GatedRow>,
    val demoSeller: Boolean,
    val book: (rowId: String) -> Unit,
    val refresh: () -> Unit,
)

/**
 * [ComparisonScreen] wired to the fare-reading module: live rows from Room (plus [network] quotes), a 1 s ticker for
 * freshness, the manual prompt from the latest tap, Open / Book / Booked here / refresh / typed prices.
 */
@Composable
fun ComparisonRoute(
    module: FareReadingModule,
    sessionId: String?,
    manualProvider: Provider?,
    modifier: Modifier = Modifier,
    network: NetworkQuotes? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val activeId by remember(sessionId) {
        if (sessionId != null) flowOf(sessionId) else module.comparisons.observeActiveSessionId()
    }.collectAsState(initial = sessionId)
    val id = activeId
    val stored by remember(id) {
        if (id == null) flowOf(ComparisonData.empty("")) else module.comparisons.observe(id)
    }.collectAsState(initial = ComparisonData.empty(id.orEmpty()))
    val data = remember(stored, network) {
        val ondcLeg = stored.legFor(Provider.ONDC)
        if (network != null && ondcLeg != null && network.legId == ondcLeg.id) stored.withNetworkRows(network.rows, network.demoSeller) else stored
    }
    val lastRead by module.coordinator.lastRead.collectAsState()
    val now = rememberWallClock(module.clock)
    val manual = remember(lastRead, manualProvider, id) { manualPromptFor(context, lastRead, manualProvider, id) }
    val bookedText = { app: String -> resources.getString(R.string.compare_booked_done, app) }
    val callbacks = remember(module, data, network) {
        ComparisonCallbacks(
            onBook = { provider -> module.appLauncher.open(provider) },
            onBookedHere = module.sessionCommands?.let { commands ->
                { provider: Provider ->
                    scope.launch {
                        val ok = commands.bookedHere(provider, TapIds.newTapId())
                        // The fee note can then say "10% of the fare" instead of only the cap (goal 10).
                        if (ok) bookedFareOf(data, provider)?.let { commands.recordBookedFare(provider, it) }
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
            onRefresh = { provider ->
                if (provider.isNetwork) {
                    network?.refresh?.invoke()
                } else {
                    // The next bubble tap reads this app's new price ("Refresh <app> price" on the bubble).
                    if (module.appLauncher.open(provider)) module.coordinator.requestRefresh(provider)
                }
            },
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
            onBookNetwork = network?.book,
        )
    }
    ComparisonScreen(data = data, nowMs = now, manual = manual, callbacks = callbacks, modifier = modifier)
}

/**
 * The fare of a booking the user records with "Booked in <app>": the lowest VERIFIED price of that app among the
 * chosen vehicle types (what the list showed as its price); null when no price of the app was verified.
 */
fun bookedFareOf(data: ComparisonData, provider: Provider): Int? =
    data.rows.filter { it.provider == provider && it.status == app.ridemate.core.model.QuoteStatus.VERIFIED }
        .mapNotNull { it.price?.last }
        .minOrNull()

/**
 * The form's prompt: the latest manual-mode tap of comparison [sessionId] (a tap from an earlier comparison never
 * prompts this one; a read that hit the 20 s limit counts as manual mode), or an explicit request to type a price
 * for [manualProvider].
 */
fun manualPromptFor(context: Context, lastRead: LastRead?, manualProvider: Provider?, sessionId: String? = null): ManualPrompt? {
    val sameComparison = sessionId == null || lastRead?.leg?.sessionId == sessionId
    val manual = lastRead?.let { read -> read.leg?.let { FareReadingCoordinator.manualModeOf(it, read.result) } ?: (read.result as? ReadFaresResult.ManualMode) }
        ?.takeIf { sameComparison }
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
