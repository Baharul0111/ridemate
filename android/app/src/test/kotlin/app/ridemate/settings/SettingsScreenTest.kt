package app.ridemate.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.config.KillSwitches
import app.ridemate.privacy.AccountErase
import app.ridemate.privacy.EraseResult
import app.ridemate.privacy.EraseStatus
import app.ridemate.privacy.ServerErase
import app.ridemate.testing.A11y
import app.ridemate.ui.RideMateTheme
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

    private val state = SettingsState(
        keepSearching = false,
        keepSearchingWarning = false,
        language = AppLanguage.ENGLISH,
        killSwitches = KillSwitches(),
        eraseDialog = EraseDialog.Hidden,
    )

    private fun show(s: SettingsState, actions: SettingsActions) = rule.setContent { RideMateTheme { SettingsScreen(s, "1.0.0-test", actions) } }

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
        rule.onNodeWithText("Erase my data").performClick()
        rule.onNodeWithText("Privacy contact").performScrollTo().performClick()
        rule.onNodeWithText("English").performScrollTo().assertIsSelected()
        rule.onNodeWithText("हिन्दी").performScrollTo().performClick()
        rule.onNodeWithText("Keep searching after a driver is found").performScrollTo().performClick()
        rule.onNodeWithText("About RideMate").performScrollTo().performClick()
        rule.onNodeWithText("Report a problem").performScrollTo().performClick()
        rule.onNodeWithText("ibaharul567@gmail.com · we reply within 30 days").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Version 1.0.0-test · Built with Muse Spark Open").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("notice", "mydata", "erase", "contact", "lang:HINDI", "keep:true", "about", "report"), clicks)
    }

    @Test
    fun `erase dialog shows the exact K1 wording and confirms`() {
        val clicks = mutableListOf<String>()
        show(state.copy(eraseDialog = EraseDialog.Confirm), SettingsActions(confirmErase = { clicks += "confirm" }, dismissErase = { clicks += "cancel" }))
        rule.onNode(isDialog()).assertIsDisplayed()
        rule.onNodeWithText("Erase your data?").assertIsDisplayed()
        rule.onNodeWithText(k1).assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Erase").performClick()
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
        rule.onNodeWithText("Your RideMate data and account were erased.").assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()
        assertEquals(1, finished)

        dialog = EraseDialog.Result(EraseStatus.NeedsReauth)
        rule.onNodeWithText("Sign in with Google once more to finish deleting your account.").assertIsDisplayed()
        rule.onNodeWithText("Sign in with Google").performClick()
        assertEquals(1, reauth)

        dialog = EraseDialog.Result(EraseStatus.Finished(EraseResult(ServerErase.FAILED, AccountErase.SKIPPED, signedOut = false)))
        rule.onNodeWithText("We couldn't finish erasing your account. Email ibaharul567@gmail.com and we'll do it within 30 days.")
            .assertIsDisplayed()

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
        rule.onNodeWithText("Play fair with drivers").assertIsDisplayed()
        rule.onNodeWithText("Turn on").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("on", "off"), clicks)
        rule.onNodeWithText("Keep searching after a driver is found").performScrollTo().assertIsOn()
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
        rule.onNodeWithText("Built with Muse Spark Open").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Developer mode").assertDoesNotExist()
        A11y.assertClickablesAccessible(rule)
    }
}
