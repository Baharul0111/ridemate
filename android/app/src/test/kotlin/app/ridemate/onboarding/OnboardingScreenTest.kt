package app.ridemate.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.testing.A11y
import app.ridemate.testing.FakeNotifier
import app.ridemate.testing.FakePermissions
import app.ridemate.testing.FakeRideWatch
import app.ridemate.testing.ShellTestData
import app.ridemate.ui.InMemoryKeyValueStore
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.components.RmTags
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = OnboardingStore(InMemoryKeyValueStore())
    private val permissions = FakePermissions()
    private val notifier = FakeNotifier()
    private val controller = OnboardingController(
        store, permissions, ShellTestData.XIAOMI, ShellTestData.authGateway(),
        HealthCheck(permissions, notifier, { FakeRideWatch(notifier) }), scope,
    )
    private var closed = false
    private val requested = mutableListOf<OnboardingStep>()

    @After
    fun tearDown() = scope.cancel()

    private fun show() {
        rule.setContent {
            RideMateTheme {
                val state by controller.state.collectAsState()
                OnboardingScreen(
                    state,
                    OnboardingActions(
                        confirmAdult = controller::confirmAdult,
                        declareUnderAge = controller::declareUnderAge,
                        closeApp = { closed = true },
                        acceptNotice = controller::acceptNotice,
                        continueWithoutGoogle = controller::continueWithoutGoogle,
                        request = { requested += it },
                        finish = controller::finish,
                        runHealthCheck = { controller.runHealthCheck() },
                        skipHealthCheck = controller::skipHealthCheck,
                        start = controller::complete,
                    ),
                )
            }
        }
    }

    /** Body content scrolls; the step's buttons are pinned below the scroll area, so scroll only when there is one. */
    private fun tap(text: String) {
        val node = rule.onNodeWithText(text)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    /** Sheets live in their own window; semantic clicks don't depend on where Robolectric places it. */
    private fun press(description: String) =
        rule.onNodeWithContentDescription(description).performSemanticsAction(SemanticsActions.OnClick)

    private fun closeSheet() {
        press("Close")
        rule.waitForIdle()
        rule.onNodeWithTag(RmTags.INFO_SHEET).assertDoesNotExist()
    }

    @Test
    fun `under 18 is blocked with no way forward`() {
        show()
        rule.onNodeWithText("Are you 18 or older?").assertIsDisplayed()
        A11y.assertClickablesAccessible(rule)
        // The full 18+ reason is one tap away, verbatim.
        press("Why: Are you 18 or older?")
        rule.onNodeWithText(
            "RideMate is for adults only. India's data protection law and the licence of the AI model RideMate uses require this check.",
        ).assertExists()
        closeSheet()
        tap("No, I'm under 18")
        rule.onNodeWithText("RideMate is for adults only").assertIsDisplayed()
        rule.onNodeWithText("Yes, I'm 18 or older").assertDoesNotExist()
        rule.onNodeWithText("I agree, continue").assertDoesNotExist()
        tap("Close RideMate")
        assertTrue(closed)
        assertEquals(OnboardingStep.UNDER_AGE_BLOCKED, controller.snapshot().step)
    }

    @Test
    fun `steps follow the order, each says why and shows its state in words`() {
        show()
        tap("Yes, I'm 18 or older")
        rule.onNodeWithText("Step 2 of 10").assertIsDisplayed()
        rule.onNodeWithText("Privacy, simply").assertIsDisplayed()
        rule.onNodeWithText("AI processing happens on Modal's servers in the USA.").assertIsDisplayed()
        // Every DPDP line stays one tap away, K1 Erase wording verbatim.
        tap("Read full notice")
        rule.onNodeWithTag(RmTags.INFO_SHEET).assertExists()
        rule.onNodeWithText(
            "Erase deletes your account, your RideMate data on this phone and on our server, except metadata logs " +
                "(1 year, required by law) and an anonymous usage counter (48 h, abuse prevention).",
        ).performScrollTo().assertIsDisplayed()
        closeSheet()
        tap("I agree, continue")

        rule.onNodeWithText("Sign in for AI reading").assertIsDisplayed()
        rule.onAllNodesWithText("Sign in with Google").assertCountEquals(1) // the button
        rule.onNodeWithText("No sign-in? You type prices. Reminders still work.").assertIsDisplayed()
        A11y.assertClickablesAccessible(rule)
        tap("Skip, I'll type prices")

        rule.onAllNodesWithText("Allow notifications").assertCountEquals(2) // title and button
        rule.onNodeWithText("For reminders to cancel extra bookings.").assertIsDisplayed()
        // The full reason sits behind the row's ⓘ.
        press("Why: Notifications")
        rule.onNodeWithText("RideMate reminds you to cancel extra bookings, so it needs to show notifications.").assertExists()
        closeSheet()
        // State in words with an icon, never by colour alone — for every permission in the checklist.
        rule.onNodeWithContentDescription("Notifications: Not allowed yet").assertIsDisplayed()
        rule.onNodeWithContentDescription("Background running: Not allowed yet").performScrollTo().assertIsDisplayed()
        A11y.assertClickablesAccessible(rule)
        tap("Not now")

        rule.onNodeWithText("Let RideMate see ride-app notifications").assertIsDisplayed()
        tap("Open notification access")
        assertEquals(listOf(OnboardingStep.NOTIFICATION_ACCESS), requested)
        // The user turned it on in Settings and came back: the step advances by itself.
        permissions.grant(AppPermission.NOTIFICATION_ACCESS)
        controller.refresh()
        rule.onNodeWithText("Allow the floating button").assertIsDisplayed()
        rule.onNodeWithContentDescription("Notification access: Allowed").performScrollTo().assertIsDisplayed()
        tap("Not now")
        rule.onNodeWithText("Use your location for pickup").assertIsDisplayed()
        tap("Not now, I'll type my pickup")

        // Xiaomi: the battery step also explains autostart, in an illustrated sheet.
        rule.onNodeWithText("Keep reminders on time").assertIsDisplayed()
        rule.onNodeWithText("One more setting on your phone").performScrollTo().assertIsDisplayed()
        tap("Show me")
        rule.onNodeWithText("Open this setting").assertExists()
        closeSheet()
        tap("Not now")

        rule.onNodeWithText("Quick self-test").assertIsDisplayed()
        tap("Run the self-test")
        // Notifications were never allowed, so the check says exactly that and how to fix it.
        rule.onNodeWithText("Notifications are off").performScrollTo().assertIsDisplayed()
        A11y.assertClickablesAccessible(rule)
        tap("Continue anyway")

        rule.onNodeWithText("You're all set").assertIsDisplayed()
        rule.onNodeWithText("The self-test didn't pass").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Notification access: Allowed").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Floating button: Not allowed yet").performScrollTo().assertIsDisplayed()
        A11y.assertClickablesAccessible(rule)
        tap("Start using RideMate")
        assertTrue(store.answers.value.completed)
    }

    @Test
    fun `at 200 percent font size every step still reaches its buttons`() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                RideMateTheme {
                    val state by controller.state.collectAsState()
                    OnboardingScreen(
                        state,
                        OnboardingActions(
                            confirmAdult = controller::confirmAdult,
                            acceptNotice = controller::acceptNotice,
                            continueWithoutGoogle = controller::continueWithoutGoogle,
                            finish = controller::finish,
                        ),
                    )
                }
            }
        }
        tap("Yes, I'm 18 or older")
        tap("I agree, continue")
        tap("Skip, I'll type prices")
        rule.onAllNodesWithText("Allow notifications")[0].assertIsDisplayed()
        rule.onNodeWithText("Not now").assertIsDisplayed()
        A11y.assertClickablesAccessible(rule)
    }
}
