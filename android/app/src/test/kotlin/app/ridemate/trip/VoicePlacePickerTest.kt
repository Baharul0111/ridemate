package app.ridemate.trip

import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Place
import app.ridemate.net.ApiResult
import app.ridemate.net.PlaceCandidate
import app.ridemate.net.PlacePickResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Voice → Geocoder candidates → place-pick → the user confirms (fakes only; no network). */
@OptIn(ExperimentalCoroutinesApi::class)
class VoicePlacePickerTest {
    private val near = LatLng(26.5, 91.0)
    private val geocoder = FakeGeocoder(answer = { GeocodeResult.Found(airportPlaces) })
    private val api = PlacePickApi { ApiResult.Ok(PlacePickResponse("c2")) }
    private val picker = VoicePlacePicker(geocoder, api)

    private val airportPlaces = listOf(
        Place("LGBI Airport, Guwahati", LatLng(26.1061, 91.5859)),
        Place("Airport Road, Barpeta", LatLng(26.32, 91.0)),
        Place("Airport Colony, Tezpur", LatLng(26.7, 92.8)),
    )

    @Test
    fun `the model picks a candidate id and the geocoder label is offered for confirmation`() = runTest {
        val outcome = picker.pick("  airport road  ", VoiceLanguage.HINDI, near, sessionId = "s-1") as PlacePickOutcome.Picked

        assertEquals(PlaceSuggestion("c2", "Airport Road, Barpeta", LatLng(26.32, 91.0)), outcome.pick)
        assertEquals(listOf("c1", "c2", "c3"), outcome.candidates.map { it.id })
        val request = api.requests.single()
        assertEquals("airport road", request.transcript)
        assertEquals("hi-IN", request.lang)
        assertEquals(
            listOf(
                PlaceCandidate("c1", "LGBI Airport, Guwahati"),
                PlaceCandidate("c2", "Airport Road, Barpeta"),
                PlaceCandidate("c3", "Airport Colony, Tezpur"),
            ),
            request.candidates,
        )
        assertEquals(FakeGeocoder.Call("airport road", 5, near), geocoder.calls.single())
    }

    @Test
    fun `none from the model means the user picks from the list`() = runTest {
        api.answer = { ApiResult.Ok(PlacePickResponse(null)) }
        val outcome = picker.pick("airport", VoiceLanguage.ENGLISH, near) as PlacePickOutcome.Choose
        assertEquals(ChooseReason.MODEL_NONE, outcome.reason)
        assertEquals(3, outcome.candidates.size)
    }

    @Test
    fun `an id that is not one of the candidates is treated as none (model output is only an enum)`() = runTest {
        api.answer = { ApiResult.Ok(PlacePickResponse("c9")) }
        assertEquals(ChooseReason.MODEL_NONE, (picker.pick("airport", VoiceLanguage.ENGLISH, near) as PlacePickOutcome.Choose).reason)
        api.answer = { ApiResult.Ok(PlacePickResponse("LGBI Airport, Guwahati")) }
        assertEquals(ChooseReason.MODEL_NONE, (picker.pick("airport", VoiceLanguage.ENGLISH, near) as PlacePickOutcome.Choose).reason)
    }

    @Test
    fun `AI paused, offline, signed out or a timeout fall back to choosing from the list`() = runTest {
        val failures = listOf(
            ApiResult.AiPaused("ai_paused", "13:30", "budget"),
            ApiResult.Offline,
            ApiResult.AuthNeeded,
            ApiResult.GoogleRequired,
            ApiResult.Timeout,
            ApiResult.NotConfigured,
            ApiResult.Http(500),
        )
        failures.forEach { failure ->
            api.answer = { failure }
            val outcome = picker.pick("airport", VoiceLanguage.ENGLISH, near) as PlacePickOutcome.Choose
            assertEquals(ChooseReason.AI_UNAVAILABLE, outcome.reason)
            assertEquals(failure, outcome.failure)
            assertEquals(3, outcome.candidates.size)
        }
    }

    @Test
    fun `a place-pick call that never answers is cut off by the time limit`() = runTest {
        val never = CompletableDeferred<ApiResult<PlacePickResponse>>()
        api.answer = { never.await() }
        val outcome = picker.pick("airport", VoiceLanguage.ENGLISH, near) as PlacePickOutcome.Choose
        assertEquals(ChooseReason.AI_UNAVAILABLE, outcome.reason)
        assertEquals(ApiResult.Timeout, outcome.failure)
        assertEquals(VoicePlacePicker.PICK_TIMEOUT_MS, currentTime)
    }

    @Test
    fun `at most five candidates go to the model`() = runTest {
        geocoder.answer = { q -> GeocodeResult.Found(FakeGeocoder.placesFor(q, count = 7)) }
        picker.pick("market", VoiceLanguage.BENGALI, near)
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), api.requests.single().candidates.map { it.id })
        assertEquals("bn-IN", api.requests.single().lang)
    }

    @Test
    fun `one candidate needs no model call but is still confirmed by the user`() = runTest {
        geocoder.answer = { GeocodeResult.Found(airportPlaces.take(1)) }
        val outcome = picker.pick("lgbi", VoiceLanguage.ASSAMESE, near) as PlacePickOutcome.Choose
        assertEquals(ChooseReason.SINGLE_CANDIDATE, outcome.reason)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `nothing found, no geocoder, a failed lookup and an empty transcript`() = runTest {
        geocoder.answer = { GeocodeResult.Found(emptyList()) }
        assertEquals(PlacePickOutcome.NoCandidates, picker.pick("xyzzy", VoiceLanguage.ENGLISH, near))
        geocoder.answer = { GeocodeResult.Failed }
        assertEquals(PlacePickOutcome.LookupFailed, picker.pick("airport", VoiceLanguage.ENGLISH, near))
        geocoder.isAvailable = false
        assertEquals(PlacePickOutcome.GeocoderUnavailable, picker.pick("airport", VoiceLanguage.ENGLISH, near))
        assertEquals(PlacePickOutcome.NoCandidates, picker.pick("   ", VoiceLanguage.ENGLISH, near))
        assertTrue(api.requests.isEmpty())
    }
}
