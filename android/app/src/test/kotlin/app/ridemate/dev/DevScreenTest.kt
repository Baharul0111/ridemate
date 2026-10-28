package app.ridemate.dev

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.data.RecorderPostEntity
import app.ridemate.dev.CaptureTestResult.Done
import app.ridemate.integration.PrecheckGate
import app.ridemate.integration.RideMateScreens
import app.ridemate.net.ConfigCheckBudget
import app.ridemate.net.ConfigCheckResponse
import app.ridemate.testing.A11y
import app.ridemate.testing.TestRideMateApp
import app.ridemate.ui.RideMateTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DevScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val state = DevState(
        enabled = true,
        recorderOn = true,
        recent = listOf(
            RecorderPostEntity(
                packageName = "com.olacabs.customer", channelId = "trip", category = "status", isOngoing = true, notifId = 4, tag = null,
                titleMasked = "Your driver <m> is arriving", textMasked = null, redacted = false, removalReason = 8, atUtc = 1_793_000_000_000L,
            ),
        ),
        captureTest = CaptureTestStatus.Finished(Done(blackFrame = true, bubbleVisible = false)),
        golden = emptyList(),
        budget = BudgetStatus.Loaded(ConfigCheckResponse(aiEnabled = true, capProfile = "judging", budget = ConfigCheckBudget(2.0, 80.0, 1.0, 12.0, 3.0, 1.5, "2027-01-26"))),
        ownerAlertsOn = false,
        ownerAlerts = OwnerAlertsStatus.Idle,
        message = null,
    )

    @Test
    fun `developer screen is accessible and reads results as words`() {
        val clicks = mutableListOf<String>()
        rule.setContent {
            RideMateTheme {
                DevScreen(
                    state,
                    imageFile = { File("missing") },
                    actions = DevActions(exportRecorder = { clicks += "export" }, runCaptureTest = { clicks += "capture" }, disable = { clicks += "off" }),
                )
            }
        }
        A11y.assertClickablesAccessible(rule)
        rule.onNodeWithText("Your driver <m> is arriving").assertIsDisplayed()
        rule.onNodeWithText("Export as JSON").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Black frame: Yes").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Bubble visible: No").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Run capture test").performScrollTo().performClick()
        rule.onNodeWithText("$2.00 of $80.00 (2%)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("$1.50 of $3.00 (50%)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Judging week").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Turn off developer mode").performScrollTo().performClick()
        assertEquals(listOf("export", "capture", "off"), clicks)
    }

    @Test
    fun `developer screen says it is off for everyone else`() {
        rule.setContent { RideMateTheme { DevScreen(state.copy(enabled = false), { File("x") }, DevActions()) } }
        rule.onNodeWithText("Developer mode is off").assertIsDisplayed()
        rule.onNodeWithText("Recorder").assertDoesNotExist()
    }

    @Test
    fun `the shell wires its defaults and erase hook into the graph`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TestRideMateApp>()
        val graph = app.graph
        val shell = graph.appShell
        assertSame(shell, graph.appShell)
        assertTrue(graph.featureBindings.screens is RideMateScreens)
        assertTrue(shell.preSessionGate is PrecheckGate)
        assertSame(graph.rideWatchStatus, shell.rideWatchSelfTest)
        assertSame(graph.captureDevTools, graph.featureBindings.captureTester)
        assertFalse("the Recorder is off until the owner turns developer mode on", shell.recorderSink.isRecording)

        shell.onboardingStore.markCompleted()
        shell.settingsStore.setKeepSearchingDefault(true)
        graph.eraseUseCase.erase()
        assertFalse(shell.onboardingStore.answers.value.completed)
        assertFalse("keep-searching is back to its default (off)", shell.settingsStore.keepSearchingDefault.value)
    }
}
