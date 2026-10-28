package app.ridemate.integration

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ridemate.AppGraph
import app.ridemate.R
import app.ridemate.compare.ComparisonRoute
import app.ridemate.core.format.IndianFormat
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.state.TapId
import app.ridemate.demo.DemoController
import app.ridemate.demo.DemoScreen
import app.ridemate.notify.GuardianRoute
import app.ridemate.notify.GuardianSheetRoute
import app.ridemate.ondc.OndcMessages
import app.ridemate.ondc.OndcPhase
import app.ridemate.ondc.OndcQuoteView
import app.ridemate.ondc.OndcView
import app.ridemate.trip.TripRoute
import app.ridemate.ui.ChipTone
import app.ridemate.ui.FeatureNavigator
import app.ridemate.ui.FeatureScreens
import app.ridemate.ui.InfoCard
import app.ridemate.ui.PrimaryButton
import app.ridemate.ui.ScreenScaffold
import app.ridemate.ui.SecondaryButton
import app.ridemate.ui.rememberScopedModel
import java.util.UUID

/**
 * The real screens behind the shell's navigation (`FeatureScreens`): trip input (A1), the comparison list with the
 * open-network row (A2 + ONDC), the ride guardian (A3) and Try the demo. The guardian's open sheet is shown over
 * every screen while a session runs ([SessionSheets]).
 */
class RideMateScreens(private val graph: AppGraph) : FeatureScreens {

    @Composable
    override fun TripInput(navigator: FeatureNavigator) {
        ScreenScaffold(title = stringResource(R.string.ui_feature_trip), onBack = navigator::back, scrollable = false) {
            Box(Modifier.fillMaxSize()) {
                TripRoute(
                    onStartComparison = { plan ->
                        // Refused (I8): the guardian shows the bookings still to cancel.
                        graph.comparisonStarter.start(plan)?.let(navigator::comparison) ?: navigator.guardian(null)
                    },
                    onTryDemo = navigator::demo,
                )
            }
        }
    }

    @Composable
    override fun Comparison(sessionId: String?, navigator: FeatureNavigator) {
        val ondc by graph.ondcClient.view.collectAsStateWithLifecycle()
        ScreenScaffold(title = stringResource(R.string.ui_feature_compare), onBack = navigator::back, scrollable = false) {
            if (ondc.phase != OndcPhase.IDLE) {
                OndcPanel(
                    view = ondc,
                    onBook = { quote -> ondc.legId?.let { graph.guardianRuntime.onUserAction(UserAction.OndcBook(it, quote.itemId, newTap())) } },
                    onCancel = { ondc.legId?.let { graph.guardianRuntime.onUserAction(UserAction.OndcCancel(it, newTap())) } },
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                ComparisonRoute(graph.fareReading, sessionId, manualProvider = null)
            }
            SecondaryButton(stringResource(R.string.ui_feature_guardian), onClick = { navigator.guardian(sessionId) })
        }
    }

    @Composable
    override fun Guardian(sessionId: String?, sheet: String?, navigator: FeatureNavigator) {
        ScreenScaffold(title = stringResource(R.string.ui_feature_guardian), onBack = navigator::back, scrollable = false) {
            Box(Modifier.fillMaxSize()) {
                GuardianRoute(graph.guardianRuntime, graph.guardian.cancelSteps)
            }
        }
    }

    @Composable
    override fun Demo(navigator: FeatureNavigator) {
        // The demo ends when its screen leaves the back stack (not on rotation): the holder is cleared only then.
        val controller = rememberScopedModel("demo") { scope -> DemoSessionScope(graph.demoController, scope) }.controller
        ScreenScaffold(title = stringResource(R.string.ui_feature_demo), onBack = navigator::back, scrollable = false) {
            Box(Modifier.fillMaxSize()) { DemoScreen(controller) }
        }
    }

    /** The guardian's most urgent open sheet, over any screen while a session runs. */
    @Composable
    override fun SessionSheets(navigator: FeatureNavigator) {
        val status by graph.guardianRuntime.status.collectAsStateWithLifecycle()
        if (status.sessionOpen || status.needsForegroundService) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                GuardianSheetRoute(graph.guardianRuntime, graph.guardian.cancelSteps)
            }
        }
    }

    private fun newTap(): TapId = TapId(UUID.randomUUID().toString())
}

/** Keeps Try the demo running across rotation and stops it when the demo screen is left for good. */
class DemoSessionScope(val controller: DemoController, scope: kotlinx.coroutines.CoroutineScope) {
    init {
        scope.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { controller.stop() }
    }
}

/** The open-network row (goal 17): quotes as "network quote" rows, Book after the user's tap, the booking's status. */
@Composable
fun OndcPanel(view: OndcView, onBook: (OndcQuoteView) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status = OndcMessages.status(context, view)
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        InfoCard(
            title = stringResource(R.string.ondc_name),
            body = status,
            tone = if (view.phase == OndcPhase.UNAVAILABLE || view.phase == OndcPhase.FAILED) ChipTone.WARNING else ChipTone.NEUTRAL,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            if (view.phase == OndcPhase.QUOTES || view.phase == OndcPhase.SEARCHING) {
                view.quotes.forEach { quote ->
                    val className = stringResource(OndcMessages.classRes(quote.row.vehicleClass))
                    val price = quote.row.price?.let { IndianFormat.rupeeRange(it) }.orEmpty()
                    Text(
                        "${quote.row.displayLabel.ifBlank { stringResource(R.string.ondc_name) }} · $className · $price",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(OndcMessages.quoteLabel(context, quote), style = MaterialTheme.typography.labelMedium)
                    PrimaryButton(
                        text = stringResource(R.string.ondc_action_book),
                        onClick = { onBook(quote) },
                        contentDescription = stringResource(R.string.ondc_action_book_description) + ". " +
                            OndcMessages.quoteDescription(context, quote, className),
                    )
                }
            }
            if (view.phase == OndcPhase.BOOKED || view.phase == OndcPhase.ARRIVING) {
                SecondaryButton(stringResource(R.string.ondc_action_cancel), onClick = onCancel)
            }
        }
    }
}
