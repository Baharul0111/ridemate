package app.ridemate.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.config.KillSwitches
import app.ridemate.privacy.AccountErase
import app.ridemate.privacy.EraseResult
import app.ridemate.privacy.EraseStatus
import app.ridemate.privacy.ServerErase
import app.ridemate.testing.A11y
import app.ridemate.ui.RideMateTheme
import app.ridemate.ui.components.RmTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val k1 = "Erase deletes your account, your RideMate data on this phone and on our server, except metadata logs " +
        "(1 year, required by law) and an anonymous usage counter (48 h, abuse prevention)."

    private val fairPlay = "Keeping more than one booking means drivers may drive towards you and then be cancelled, which costs " +
        "them time and fuel. Ride apps may also charge a cancellation fee (up to ₹100 under India's rules). Turn this on only " +
        "if you'll cancel extra bookings quickly."

    private val state = SettingsState(
        keepSearching = false,
        keepSearchingWarning = false,
        language = AppLanguage.ENGLISH,
        killSwitches = KillSwitches(),
        eraseDialog = EraseDialog.Hidden,
    )

    private fun show(s: SettingsState, actions: SettingsActions) = rule.setContent { RideMateTheme { SettingsScreen(s, "1.0.0-test", actions) } }

    /** Sheets live in their own window; semantic clicks don't depend on where Robolectric places it. */
    private fun tapInSheet(text: String) = rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun `settings rows are accessible and lead where they say`() {
        val clicks = mutableListOf<String>()
        show(
            state,
            SettingsActions(
                privacyNotice = { clicks += "notice" },
                myData = { clicks += "mydata" },
                requestErase = { clicks += "erase" },
                contactPrivacy = { clicks += "contact" },
                setLanguage = { clicks += "lang:$it" },
                setKeepSearching = { clicks += "keep:$it" },
                about = { clicks += "about" },
                reportProblem = { clicks += "report" },
            ),
        )
        A11y.assertClickablesAccessible(rule)
        rule.onNodeWithText("Privacy notice").performClick()
        rule.onNodeWithText("My data").performClick()
        rule.onNodeWithText("Erase my data").performScrollTo().performClick()
        rule.onNodeWithText("Privacy contact").performScrollTo().performClick()
        rule.onNodeWithText("English").performScrollTo().assertIsSelected()
        rule.onNodeWithText("हिन्दी").performScrollTo().performClick()
        rule.onNodeWithText("Keep searching").performScrollTo().performClick()
        rule.onNodeWithText("About RideMate").performScrollTo().performClick()
        rule.onNodeWithText("Report a problem").performScrollTo().performClick()
        rule.onNodeWithText("ibaharul567@gmail.com · we reply within 30 days").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Version 1.0.0-test · Built with Muse Spark Open").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("notice", "mydata", "erase", "contact", "lang:HINDI", "keep:true", "about", "report"), clicks)
    }

    @Test
    fun `explanations are one tap away, complete, in sheets`() {
        show(state, SettingsActions())
        rule.onNodeWithText(fairPlay).assertDoesNotExist()
        rule.onNodeWithContentDescription("About keep searching").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(RmTags.INFO_SHEET).assertExists()
        rule.onNodeWithText(
            "Off by default. When on, RideMate tells you if a much closer driver turns up, and changes nothing unless you say yes.",
        ).assertExists()
        rule.onNodeWithText("Play fair with drivers").assertExists()
        rule.onNodeWithText(fairPlay).assertExists()
        rule.onNodeWithContentDescription("Close").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText(fairPlay).assertDoesNotExist()

        rule.onNodeWithContentDescription("About status").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(
            "RideMate's owner can switch features off remotely if a ride app changes. When AI reading is off, you type prices " +
                "and the guardian keeps working.",
        ).assertExists()
    }

    @Test
    fun `erase sheet shows the exact K1 wording and confirms`() {
        val clicks = mutableListOf<String>()
        show(state.copy(eraseDialog = EraseDialog.Confirm), SettingsActions(confirmErase = { clicks += "confirm" }, dismissErase = { clicks += "cancel" }))
        rule.waitForIdle()
        rule.onNodeWithTag(RmTags.INFO_SHEET).assertExists()
        rule.onNodeWithText("Erase your data?").assertExists()
        rule.onNodeWithText(k1).assertExists()
        rule.onNodeWithText("This can't be undone. RideMate will start again from the beginning.").assertExists()
        tapInSheet("Cancel")
        tapInSheet("Erase")
        assertEquals(listOf("cancel", "confirm"), clicks)
    }

    @Test
    fun `erase results say what happened and what to do next`() {
        var finished = 0
        var reauth = 0
        val actions = SettingsActions(eraseFinished = { finished++ }, reauthErase = { reauth++ })
        val done = EraseStatus.Finished(EraseResult(ServerErase.DONE, AccountErase.DELETED, signedOut = true))
        var dialog: EraseDialog by mutableStateOf(EraseDialog.Result(done))
        rule.setContent { RideMateTheme { EraseDialogHost(dialog, actions) } }
        rule.waitForIdle()
        rule.onNodeWithText("Your RideMate data and account were erased.").assertExists()
        tapInSheet("OK")
        assertEquals(1, finished)

        dialog = EraseDialog.Result(EraseStatus.NeedsReauth)
        rule.waitForIdle()
        rule.onNodeWithText("Sign in with Google once more to finish deleting your account.").assertExists()
        tapInSheet("Sign in with Google")
        assertEquals(1, reauth)
        tapInSheet("Later")
        assertEquals(2, finished)

        dialog = EraseDialog.Result(EraseStatus.Finished(EraseResult(ServerErase.FAILED, AccountErase.SKIPPED, signedOut = false)))
        rule.waitForIdle()
        rule.onNodeWithText("We couldn't finish erasing your account. Email ibaharul567@gmail.com and we'll do it within 30 days.")
            .assertExists()

        dialog = EraseDialog.Working
        rule.onNodeWithText("Erasing…").assertIsDisplayed()
    }

    @Test
    fun `keep searching shows the fair-play warning before it turns on`() {
        val clicks = mutableListOf<String>()
        show(
            state.copy(keepSearching = true, keepSearchingWarning = true),
            SettingsActions(confirmKeepSearching = { clicks += "on" }, dismissKeepSearching = { clicks += "off" }),
        )
        rule.waitForIdle()
        rule.onNodeWithText("Play fair with drivers").assertExists()
        rule.onNodeWithText(fairPlay).assertExists()
        tapInSheet("Turn on")
        tapInSheet("Cancel")
        assertEquals(listOf("on", "off"), clicks)
        rule.onNodeWithText("Keep searching").performScrollTo().assertIsOn()
    }

    @Test
    fun `about shows version, certificate and Built with Muse Spark Open`() {
        var taps = 0
        rule.setContent {
            RideMateTheme {
                AboutScreen(
                    AboutUiState("1.0.0-test", 1, app.ridemate.update.UpdateState(), checked = true, certificates = listOf("AB:CD"), developerModeOn = false, tapOutcome = null),
                    AboutActions(tapVersion = { taps++ }),
                )
            }
        }
        rule.onNodeWithText("1.0.0-test (1)").performClick()
        assertEquals(1, taps)
        rule.onNodeWithText("You have the latest version").assertIsDisplayed()
        rule.onNodeWithText("AB:CD").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Copy fingerprint").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Built with Muse Spark Open").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Developer mode").assertDoesNotExist()
        A11y.assertClickablesAccessible(rule)

        rule.onNodeWithContentDescription("About Muse Spark Open").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("RideMate reads fare screens with Meta's open-weights Muse Spark Open model, running on Modal.").assertExists()
    }

    @Test
    fun `my data lists what is kept and offers erase and contact`() {
        val clicks = mutableListOf<String>()
        rule.setContent {
            RideMateTheme {
                MyDataScreen(listOf("Rides: 2", "Signed in: no"), onBack = {}, onErase = { clicks += "erase" }, onContact = { clicks += "contact" })
            }
        }
        A11y.assertClickablesAccessible(rule)
        rule.onNodeWithText("Rides: 2").assertIsDisplayed()
        rule.onNodeWithText("Signed in: no").assertIsDisplayed()
        rule.onNodeWithText("Email the privacy contact").performScrollTo().performClick()
        rule.onNodeWithText("Erase my data").performScrollTo().performClick()
        assertEquals(listOf("contact", "erase"), clicks)
        rule.onNodeWithContentDescription("About my data").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("This is everything RideMate keeps on this phone. Ride data is deleted a day after the ride.").assertExists()
    }
}
