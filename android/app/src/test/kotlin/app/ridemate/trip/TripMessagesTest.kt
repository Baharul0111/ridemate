package app.ridemate.trip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.R
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Provider
import app.ridemate.launch.LaunchMessages
import app.ridemate.launch.LaunchOutcome
import app.ridemate.launch.LaunchResult
import app.ridemate.launch.PasteDropChip
import app.ridemate.net.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class TripMessagesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val here = TripPlace("Barpeta Road", LatLng(26.5, 90.97))
    private val there = TripPlace("LGBI Airport", LatLng(26.1, 91.58))
    private val ready = TripUiState(
        pickup = PickupState.Current(here),
        drop = there,
        apps = listOf(AppChoice(Provider.OLA, installed = true, selected = true)),
    )

    @Test
    fun `the compare hint says what is missing next`() {
        assertNull(TripMessages.missingHint(ready))
        assertEquals(R.string.trip_need_pickup, TripMessages.missingHint(ready.copy(pickup = PickupState.Locating)))
        assertEquals(R.string.trip_need_confirm, TripMessages.missingHint(ready.copy(drop = null, pending = PendingDrop(there, DropSource.SEARCH))))
        assertEquals(R.string.trip_need_drop, TripMessages.missingHint(ready.copy(drop = null)))
        assertEquals(R.string.trip_need_app, TripMessages.missingHint(ready.copy(apps = emptyList())))
        assertEquals(R.string.trip_need_vehicle, TripMessages.missingHint(ready.copy(vehicles = emptySet())))
        assertNull(TripMessages.missingHint(ready.copy(drop = null, checking = true)))
    }

    @Test
    fun `every notice has wording that says what to do next`() {
        val notices = SpeechError.entries.map { TripNotice.Voice(it, VoiceLanguage.ASSAMESE) } + listOf(
            TripNotice.NothingFoundFor("xyz"),
            TripNotice.PickFromList(ChooseReason.MODEL_NONE, null),
            TripNotice.PickFromList(ChooseReason.AI_UNAVAILABLE, ApiResult.Offline),
            TripNotice.LookupFailed,
            TripNotice.NoMapsApp,
            TripNotice.MicDenied,
        )
        notices.forEach { assertTrue(it.toString(), TripMessages.noticeText(context, it).isNotBlank()) }
        assertEquals(
            "Voice typing in Assamese isn't available on this phone. Try another language, or type the address.",
            TripMessages.noticeText(context, TripNotice.Voice(SpeechError.LANGUAGE_UNSUPPORTED, VoiceLanguage.ASSAMESE)),
        )
    }

    @Test
    fun `an AI pause shows the resume time in IST, 12-hour`() {
        val text = TripMessages.noticeText(
            context,
            TripNotice.PickFromList(ChooseReason.AI_UNAVAILABLE, ApiResult.AiPaused("ai_paused", "13:30", "budget")),
        )
        assertEquals("Automatic matching resumes at 1:30 PM (IST). Pick your destination from the list.", text)
    }

    @Test
    @Config(qualifiers = "hi")
    fun `hindi notices`() {
        val hi = RuntimeEnvironment.getApplication()
        assertEquals("संदेश हटाएँ", hi.getString(R.string.trip_notice_dismiss))
        assertTrue(TripMessages.noticeText(hi, TripNotice.NoMapsApp).contains("मैप"))
    }

    @Test
    fun `launch results explain the next step`() {
        val chip = PasteDropChip(Provider.OLA, "LGBI Airport", 0)
        assertEquals("Paste drop", LaunchMessages.pasteChipLabel(context))
        assertEquals(
            "Drop address copied: LGBI Airport. In Ola, long-press the drop box and tap Paste.",
            LaunchMessages.pasteChipDescription(context, chip),
        )
        assertTrue(LaunchMessages.pasteChipCopied(context, chip).contains("Ola"))
        assertNull(LaunchMessages.resultText(context, LaunchResult(Provider.UBER, LaunchOutcome.OPENED)))
        assertTrue(LaunchMessages.resultText(context, LaunchResult(Provider.OLA, LaunchOutcome.OPENED, dropCopied = true))!!.contains("Paste"))
        listOf(LaunchOutcome.NOTIFICATION_POSTED, LaunchOutcome.BLOCKED, LaunchOutcome.NOT_INSTALLED, LaunchOutcome.FAILED).forEach {
            assertTrue(LaunchMessages.resultText(context, LaunchResult(Provider.RAPIDO, it))!!.contains("Rapido"))
        }
        assertNull(LaunchMessages.resultText(context, LaunchResult(Provider.DEMO_PEPPER, LaunchOutcome.NOT_A_RIDE_APP)))
    }
}
