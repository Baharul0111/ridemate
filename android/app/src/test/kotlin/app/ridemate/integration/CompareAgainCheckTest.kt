package app.ridemate.integration

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.R
import app.ridemate.precheck.FixAction
import app.ridemate.precheck.IssueSeverity
import app.ridemate.precheck.PrecheckIssue
import app.ridemate.precheck.PrecheckKind
import app.ridemate.precheck.PrecheckReport
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Compare again (same trip)" runs the same pre-session check as Compare before any app opens again (goal 20). */
@RunWith(AndroidJUnit4::class)
class CompareAgainCheckTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val blind = PrecheckIssue(PrecheckKind.LISTENER_OFF, IssueSeverity.BLOCKING, listOf(FixAction.OPEN_NOTIFICATION_ACCESS))
    private val battery = PrecheckIssue(PrecheckKind.BATTERY_RESTRICTED, IssueSeverity.WARNING, listOf(FixAction.REQUEST_BATTERY_EXEMPTION))
    private val location = PrecheckIssue(PrecheckKind.LOCATION_DENIED, IssueSeverity.INFO, listOf(FixAction.REQUEST_LOCATION))

    @Test
    fun `a blind listener blocks Compare again until it is fixed`() {
        var report = PrecheckReport(listOf(blind), 0)
        var runs = 0
        var done = 0
        compose.setContent {
            CompareAgainCheck(run = { runs++; report }, restrictedSettings = false, onTryDemo = {}, onDone = { done++ })
        }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.precheck_listener_off_title)).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.trip_continue_anyway)).assertCountEquals(0)
        assertEquals(0, done)

        // Fixed in settings: "Check again" lets the comparison go on.
        report = PrecheckReport(emptyList(), 0)
        compose.onNodeWithText(context.getString(R.string.trip_check_again)).performClick()
        compose.waitForIdle()
        assertEquals(2, runs)
        assertEquals(1, done)
    }

    @Test
    fun `warnings need Continue anyway, info notes need nothing`() {
        var done = 0
        compose.setContent {
            CompareAgainCheck(run = { PrecheckReport(listOf(battery, location), 0) }, restrictedSettings = false, onTryDemo = {}, onDone = { done++ })
        }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.precheck_battery_title)).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.precheck_location_title)).assertCountEquals(0)
        assertEquals(0, done)
        compose.onNodeWithText(context.getString(R.string.trip_continue_anyway)).performClick()
        assertEquals(1, done)
    }

    @Test
    fun `nothing to say, so the list shows at once`() {
        var done = 0
        compose.setContent {
            CompareAgainCheck(run = { PrecheckReport(listOf(location), 0) }, restrictedSettings = false, onTryDemo = {}, onDone = { done++ })
        }
        compose.waitForIdle()
        assertEquals(1, done)
    }
}
