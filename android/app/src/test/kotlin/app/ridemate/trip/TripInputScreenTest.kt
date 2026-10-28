package app.ridemate.trip

import android.content.Context
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.R
import app.ridemate.core.model.Provider
import app.ridemate.launch.FakeInstalledApps
import app.ridemate.net.ApiResult
import app.ridemate.net.PlacePickResponse
import app.ridemate.precheck.PreSessionCheck
import app.ridemate.precheck.PreSessionCheckTest
import app.ridemate.testing.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The trip screen end to end with fakes: confirm-before-compare, spoken labels, fix-it cards. */
@RunWith(AndroidJUnit4::class)
class TripInputScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock()
    private val probes = PreSessionCheckTest.FakeProbes()
    private val apps = FakeInstalledApps(installed = setOf(Provider.OLA, Provider.RAPIDO))
    private val started = mutableListOf<TripPlan>()
    private var demoTaps = 0

    private fun show(geocoder: FakeGeocoder = FakeGeocoder()): TripViewModel {
        val prefs = context.getSharedPreferences("trip-screen-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val vm = TripViewModel(
            TripDeps(
                geocoder = geocoder,
                location = FakeLocation(),
                speech = FakeSpeech(),
                placePicker = VoicePlacePicker(geocoder, PlacePickApi { ApiResult.Ok(PlacePickResponse("c1")) }),
                recentPlaces = RecentPlacesStore(prefs, clock),
                installedApps = apps,
                ondcEnabled = MutableStateFlow(false),
                precheck = PreSessionCheck(probes, { null }, clock, io = Dispatchers.Unconfined),
                currentLocationLabel = context.getString(R.string.trip_current_location),
                defaultVoiceLanguage = VoiceLanguage.ENGLISH,
                io = Dispatchers.Unconfined,
                debounceMs = 0,
            ),
        )
        compose.setContent {
            TripInputScreen(vm, onStartComparison = { started += it }, onTryDemo = { demoTaps++ })
        }
        compose.waitForIdle()
        return vm
    }

    private fun pickDrop() {
        compose.onNodeWithTag(TripTags.DROP_FIELD).performTextInput("airport")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Choose airport place 1").assertHasClickAction().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the drop must be confirmed before compare, then compare starts the trip`() {
        show()
        compose.onNodeWithText("Current location: Barpeta Road, Assam").assertExists()
        compose.onNodeWithTag(TripTags.COMPARE).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Choose and confirm your drop address to continue.").assertExists()

        pickDrop()
        compose.onNodeWithText("Is this the right place?").assertExists()
        compose.onNodeWithContentDescription("View airport place 1 on the map").assertHasClickAction()
        compose.onNodeWithTag(TripTags.COMPARE).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Confirm the drop address above to continue.").assertExists()

        compose.onNodeWithTag(TripTags.CONFIRM_YES).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Drop: airport place 1").performScrollTo().assertExists()
        compose.onNodeWithTag(TripTags.COMPARE).performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()

        val plan = started.single()
        assertEquals("airport place 1", plan.drop.label)
        assertEquals(listOf(Provider.OLA, Provider.RAPIDO), plan.apps)
    }

    @Test
    fun `controls have spoken labels and missing apps say so in words`() {
        show()
        compose.onNodeWithContentDescription("Say your destination").assertHasClickAction()
        compose.onNodeWithContentDescription("Change pickup").assertHasClickAction()
        compose.onNodeWithText("Uber · not installed").assertExists()
        compose.onNodeWithText("Bike").assertHasClickAction()
        compose.onNodeWithText("Try the demo").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, demoTaps)
    }

    @Test
    fun `a blind listener shows a blocking fix-it card instead of starting`() {
        probes.listener = false
        show()
        pickDrop()
        compose.onNodeWithTag(TripTags.CONFIRM_YES).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TripTags.COMPARE).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Turn on notification access").performScrollTo().assertExists()
        compose.onNodeWithText("Fix this to compare").assertExists()
        compose.onNodeWithContentDescription("Open notification access: Turn on notification access").assertHasClickAction()
        compose.onNodeWithText("Fix the items above, then tap Check again.").assertExists()
        assertEquals(emptyList<TripPlan>(), started)
    }

    @Test
    fun `the route builds the screen from the app graph`() {
        compose.setContent { TripRoute(onStartComparison = { started += it }, onTryDemo = { demoTaps++ }) }
        compose.waitForIdle()
        compose.onNodeWithText("Where are you going?").assertExists()
        compose.onNodeWithTag(TripTags.COMPARE).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `without a geocoder the typed address is offered and confirmed with a note`() {
        show(FakeGeocoder(isAvailable = false))
        compose.onNodeWithTag(TripTags.DROP_FIELD).performTextInput("Near Kali Mandir")
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.trip_geocoder_unavailable)).assertExists()
        compose.onNodeWithContentDescription("Use Near Kali Mandir as the address, as typed").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Is this the right place?").assertExists()
        compose.onNodeWithText(context.getString(R.string.trip_confirm_typed_note)).assertExists()
        compose.onNodeWithTag(TripTags.CONFIRM_YES).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.trip_drop_no_pin)).assertExists()
    }
}
