package app.ridemate.notify

import android.content.Context
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.guardian.FeeNote
import app.ridemate.core.guardian.FeeNoteKind
import app.ridemate.core.guardian.LegRole
import app.ridemate.core.guardian.UiAction
import app.ridemate.core.guardian.UiKey
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.ui.RideMateTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuardianScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val presenter = GuardianPresenter(GuardianText(context), CancelStepsSource.NONE)
    private val taps = mutableListOf<ActionTarget>()

    private val rapido = DriverUi("s:rapido", Provider.RAPIDO, 0, 4, EtaSource.TEMPLATE, 1_200, EtaSource.TEMPLATE)

    private val state = GuardianUiState(
        sessionId = "s",
        legs = listOf(
            LegUi("s:rapido", Provider.RAPIDO, RideState.ASSIGNED, 0, LegRole.NONE, false, booked = true, endedByApp = false, driver = rapido),
            LegUi("s:uber", Provider.UBER, RideState.CANCEL_CONFIRMED, 0, LegRole.EXTRA, false, booked = true, endedByApp = false, evidence = EvidenceRung.TEMPLATE),
            LegUi("s:ola", Provider.OLA, RideState.CANCEL_GUIDED, 0, LegRole.EXTRA, false, booked = true, endedByApp = false),
        ),
        items = listOf(
            DriverSheetItem(
                "sheet-1", UiKey.SHEET_CANCEL_OTHERS, listOf(rapido),
                listOf(LegFeeUi("s:ola", Provider.OLA, FeeNote(FeeNoteKind.FREE_WHILE_SEARCHING))), listOf(UiAction.YES_CANCEL_OTHERS, UiAction.NOT_NOW),
            ),
        ),
    )

    private fun show(s: GuardianUiState = state) = compose.setContent {
        RideMateTheme { GuardianScreen(s, presenter, onAction = { taps += it }) }
    }

    @Test
    fun `the active sheet shows its question and buttons, and a tap sends its target`() {
        show()
        compose.onNodeWithText("Cancel the others?").assertIsDisplayed()
        compose.onNodeWithText("Yes, cancel the others").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf<ActionTarget>(GuardianTap(TapScope.SHEET, "sheet-1", UiAction.YES_CANCEL_OTHERS)), taps)
        compose.onNodeWithText("Not now").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `every leg shows its state, role, proof and driver in words`() {
        show()
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Captain on the way"))
        compose.onNodeWithText("Captain on the way").assertIsDisplayed()
        list.performScrollToNode(hasText("Cancelled and proven"))
        compose.onNodeWithText("Proof: Uber's own cancelled notification").assertIsDisplayed()
        list.performScrollToNode(hasText("Cancel it in Ola now"))
        compose.onNodeWithText("Cancel it in Ola now").assertIsDisplayed()
    }

    @Test
    fun `a pending extra booking can be dismissed with a labelled 48 dp button`() {
        show()
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Stop reminding about Ola"))
        compose.onNodeWithContentDescription("Stop reminding about Ola").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf<ActionTarget>(GuardianTap(TapScope.LEG, "s:ola", UiAction.DISMISS)), taps)
    }

    @Test
    fun `the empty state says how to start`() {
        show(GuardianUiState.EMPTY)
        compose.onNodeWithText(context.getString(app.ridemate.R.string.guardian_screen_empty)).assertIsDisplayed()
    }

    @Test
    fun `a demo session is labelled as fictional`() {
        show(GuardianUiState(sessionId = "d", demo = true))
        compose.onNodeWithText("DEMO: fictional providers").assertIsDisplayed()
    }

    @Test
    fun `before anything is booked the keep-searching opt-in is offered with its warning`() {
        show(GuardianUiState(sessionId = "s", legs = listOf(LegUi("s:ola", Provider.OLA, RideState.QUOTED, 0, LegRole.NONE, false, false, false))))
        compose.onNodeWithText("Keep searching: off").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(context.getString(app.ridemate.R.string.guardian_keep_searching_warning)).assertIsDisplayed()
        assertEquals(listOf<ActionTarget>(SessionOp(SessionOpKind.TOGGLE_KEEP_SEARCHING)), taps)
    }

    @Test
    fun `the sheet host shows the most urgent item`() {
        val alert = AlertItem("s:ola", Provider.OLA, UiKey.ALERT_STILL_LIVE, listOf(UiAction.OPEN_APP, UiAction.CHECK_SCREEN, UiAction.DISMISS))
        compose.setContent {
            RideMateTheme { GuardianSheetHost(state.copy(items = state.items + alert), presenter, onAction = { taps += it }) }
        }
        compose.onNodeWithText("Ola still looks active").assertIsDisplayed()
        compose.onNodeWithText("Open Ola").performClick()
        assertEquals(listOf<ActionTarget>(GuardianTap(TapScope.LEG, "s:ola", UiAction.OPEN_APP)), taps)
    }
}
