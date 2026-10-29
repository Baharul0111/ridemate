package app.ridemate.integration

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.ridemate.precheck.FixAction
import app.ridemate.precheck.FixIntents
import app.ridemate.precheck.IssueSeverity
import app.ridemate.precheck.PrecheckCard
import app.ridemate.precheck.PrecheckReport
import app.ridemate.precheck.PrecheckRequest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ridemate.AppGraph
import app.ridemate.R
import app.ridemate.compare.ComparisonRoute
import app.ridemate.compare.NetworkQuotes
import app.ridemate.core.gate.GatedRow
import app.ridemate.core.guardian.UserAction
import app.ridemate.core.model.Provider
import app.ridemate.core.state.TapId
import app.ridemate.demo.DemoController
import app.ridemate.demo.DemoScreen
import app.ridemate.notify.GuardianRoute
import app.ridemate.notify.GuardianSheetRoute
import app.ridemate.ondc.OndcMessages
import app.ridemate.ondc.OndcPhase
import app.ridemate.ondc.OndcView
import app.ridemate.session.GuardianRuntime
import app.ridemate.trip.TripRoute
import app.ridemate.ui.ChipTone
import app.ridemate.ui.FeatureNavigator
import app.ridemate.ui.FeatureScreens
import app.ridemate.ui.InfoCard
import app.ridemate.ui.ScreenScaffold
import app.ridemate.ui.SecondaryButton
import app.ridemate.ui.rememberScopedModel
import kotlinx.coroutines.launch
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
        val network = networkQuotesOf(ondc)
        ScreenScaffold(title = stringResource(R.string.ui_feature_compare), onBack = navigator::back, scrollable = false) {
            // The quotes themselves are rows of the list below; the panel says what the network is doing.
            if (OndcPanelPhases.shows(ondc.phase)) {
                OndcPanel(
                    view = ondc,
                    onCancel = { ondc.legId?.let { graph.guardianRuntime.onUserAction(UserAction.OndcCancel(it, newTap())) } },
                )
            }
            val pendingCheck by graph.comparePrecheck.collectAsStateWithLifecycle()
            // Only for the comparison shown (or, without one named, the guardian's current session).
            val checking = pendingCheck?.takeIf { it == (sessionId ?: graph.guardianRuntime.status.value.sessionId) }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (checking != null) {
                    // "Compare again": the apps open again only after the pre-session check (goal 20).
                    CompareAgainCheck(
                        run = { compareAgainReport(checking) },
                        restrictedSettings = graph.appShell.device.restrictedSettingsPossible,
                        onTryDemo = navigator::demo,
                        onDone = { graph.comparePrecheck.compareAndSet(checking, null) },
                    )
                } else {
                    ComparisonRoute(graph.fareReading, sessionId, manualProvider = null, network = network)
                }
            }
            SecondaryButton(stringResource(R.string.ui_feature_guardian), onClick = { navigator.guardian(sessionId) })
        }
    }

    /** The pre-session check for the apps (and ONDC) of [sessionId], the same one Compare runs. */
    private suspend fun compareAgainReport(sessionId: String): PrecheckReport {
        val session = graph.db.sessionDao().get(sessionId)
        val apps = session?.selectedApps.orEmpty().filter { !it.isNetwork }.toSet()
        return graph.preSessionCheck.run(PrecheckRequest(apps, includeOndc = session?.includeOndc == true))
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

    /**
     * The network's quotes as rows of the comparison list (goals 6–7: seen-at time, greyed at 60 s, grouped by vehicle
     * type with the ride apps). Book sends the user's tap to the guardian; a stale row's tap searches again.
     */
    private fun networkQuotesOf(view: OndcView): NetworkQuotes = NetworkQuotes(
        legId = view.legId,
        rows = OndcPanelPhases.bookableRows(view),
        demoSeller = view.demoSeller,
        book = { rowId ->
            val legId = view.legId
            val itemId = OndcPanelPhases.itemIdOf(rowId)
            if (legId != null && itemId != null) graph.guardianRuntime.onUserAction(UserAction.OndcBook(legId, itemId, newTap()))
        },
        refresh = { view.legId?.let(::searchAgain) },
    )

    private fun searchAgain(legId: String) {
        graph.mainScope.launch {
            runCatching {
                val sessionId = graph.guardianRuntime.status.value.sessionId ?: return@runCatching
                if (GuardianRuntime.legIdOf(sessionId, Provider.ONDC) != legId) return@runCatching
                val session = graph.db.sessionDao().get(sessionId) ?: return@runCatching
                val pickup = session.pickup.toPlace().point
                val drop = session.drop.toPlace().point
                if (!ComparisonStarter.isKnown(pickup) || !ComparisonStarter.isKnown(drop)) return@runCatching
                graph.ondcClient.search(sessionId, legId, pickup, drop)
            }.onFailure { graph.crashReporter.recordNonFatal(it, app.ridemate.crash.CrashSite.OTHER) }
        }
    }
}

/**
 * "Compare again (same trip)" (goal 20): permissions can be switched off at any time, so the same pre-session check as
 * Compare runs before the apps open again. Blocking problems keep the apps closed until fixed ("Check again" after
 * coming back from settings); warnings need "Continue anyway"; with nothing to say the list shows at once ([onDone]).
 */
@Composable
fun CompareAgainCheck(
    run: suspend () -> PrecheckReport,
    restrictedSettings: Boolean,
    onTryDemo: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var round by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<PrecheckReport?>(null) }
    LaunchedEffect(round) {
        val r = run()
        if (!r.needsAttention) onDone() else report = r
    }
    // Back from a settings screen a card opened: check again (not on the first resume, which is just the screen opening).
    var paused by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        if (paused && report != null) round++
        onPauseOrDispose { paused = true }
    }
    val onFix: (FixAction) -> Unit = { action ->
        when (action) {
            FixAction.TRY_DEMO -> onTryDemo()
            FixAction.CHOOSE_APPS -> onDone()
            else -> FixIntents.forAction(context, action)?.let { intent ->
                val opened = runCatching { context.startActivity(intent) }.isSuccess ||
                    (action == FixAction.OPEN_NOTIFICATION_ACCESS && runCatching { context.startActivity(FixIntents.notificationAccessList()) }.isSuccess)
                if (!opened) android.widget.Toast.makeText(context, R.string.precheck_settings_missing, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val r = report
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.precheck_compare_again_heading),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (r == null) {
            Text(stringResource(R.string.trip_checking), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } else {
            r.issues.filter { it.severity != IssueSeverity.INFO }.forEach { PrecheckCard(it, onFix, restrictedSettings = restrictedSettings) }
            if (r.canProceed) {
                SecondaryButton(stringResource(R.string.trip_continue_anyway), onClick = onDone)
            } else {
                Text(stringResource(R.string.trip_fix_first), style = MaterialTheme.typography.bodyMedium)
                SecondaryButton(stringResource(R.string.trip_check_again), onClick = { round++ })
            }
        }
    }
}

/** Which ONDC phases the panel shows, and the quotes that join the comparison list. */
object OndcPanelPhases {
    private const val ROW_PREFIX = "ondc:"

    /** While quotes are listed the list says it all; the panel shows progress, problems and the booking. */
    fun shows(phase: OndcPhase): Boolean = phase != OndcPhase.IDLE && phase != OndcPhase.QUOTES

    /** Quotes that can still be booked, as list rows whose id carries the seller's item id. */
    fun bookableRows(view: OndcView): List<GatedRow> =
        if (view.phase == OndcPhase.QUOTES || view.phase == OndcPhase.SEARCHING) {
            view.quotes.map { it.row.copy(rowId = ROW_PREFIX + it.itemId) }
        } else {
            emptyList()
        }

    fun itemIdOf(rowId: String): String? = rowId.takeIf { it.startsWith(ROW_PREFIX) }?.removePrefix(ROW_PREFIX)
}

/** Keeps Try the demo running across rotation and stops it when the demo screen is left for good. */
class DemoSessionScope(val controller: DemoController, scope: kotlinx.coroutines.CoroutineScope) {
    init {
        scope.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { controller.stop() }
    }
}

/**
 * The open-network status (goal 17): searching, the booking and its cancel with the fee shown before confirm. The
 * hackathon's mock seller is labelled "ONDC demo seller" in every phase, not only on its quotes.
 */
@Composable
fun OndcPanel(view: OndcView, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status = OndcMessages.status(context, view)
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        InfoCard(
            title = OndcMessages.title(context, view.demoSeller),
            body = status,
            tone = if (view.phase == OndcPhase.UNAVAILABLE || view.phase == OndcPhase.FAILED) ChipTone.WARNING else ChipTone.NEUTRAL,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            if (view.phase == OndcPhase.BOOKED || view.phase == OndcPhase.ARRIVING) {
                SecondaryButton(stringResource(R.string.ondc_action_cancel), onClick = onCancel)
            }
        }
    }
}
