package app.ridemate.trip

import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Place
import app.ridemate.net.ApiClient
import app.ridemate.net.ApiResult
import app.ridemate.net.PlacePickRequest
import app.ridemate.net.PlacePickResponse
import app.ridemate.testing.FakeApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A scripted Geocoder: [answer] decides each search; every call is recorded. */
class FakeGeocoder(
    override var isAvailable: Boolean = true,
    var answer: suspend (query: String) -> GeocodeResult = { q -> GeocodeResult.Found(placesFor(q)) },
    var reverseLabel: String? = "Barpeta Road, Assam",
    var searchDelayMs: Long = 0,
) : PlaceGeocoder {
    data class Call(val query: String, val max: Int, val near: LatLng?)

    val calls = mutableListOf<Call>()
    val reverseCalls = mutableListOf<LatLng>()

    override suspend fun search(query: String, maxResults: Int, near: LatLng?): GeocodeResult {
        calls += Call(query, maxResults, near)
        if (searchDelayMs > 0) delay(searchDelayMs)
        if (!isAvailable) return GeocodeResult.Unavailable
        return answer(query)
    }

    override suspend fun reverse(point: LatLng): String? {
        reverseCalls += point
        return reverseLabel
    }

    companion object {
        fun placesFor(query: String, count: Int = 3): List<Place> =
            (1..count).map { i -> Place("$query place $i", LatLng(26.0 + i / 100.0, 91.0 + i / 100.0)) }
    }
}

/** An [ApiClient] whose place-pick answer is scripted; everything else is the platform fake. */
class PlacePickApi(var answer: suspend (PlacePickRequest) -> ApiResult<PlacePickResponse>) : ApiClient by FakeApiClient() {
    val requests = mutableListOf<PlacePickRequest>()

    override suspend fun placePick(request: PlacePickRequest, sessionId: String?): ApiResult<PlacePickResponse> {
        requests += request
        return answer(request)
    }
}

class FakeLocation(var permission: Boolean = true, var fix: suspend () -> LocationFix = { LocationFix.Found(HERE) }) : CurrentLocationSource {
    var calls = 0
    override fun hasPermission(): Boolean = permission
    override suspend fun current(): LocationFix {
        calls++
        return fix()
    }

    companion object {
        val HERE = LatLng(26.5032, 90.9757)
    }
}

class FakeSpeech(
    var available: Boolean = true,
    var supported: Set<VoiceLanguage>? = null,
    var script: (VoiceLanguage) -> Flow<SpeechEvent> = { flow { emit(SpeechEvent.Final("airport")) } },
) : SpeechInput {
    val listenedIn = mutableListOf<VoiceLanguage>()

    override fun isAvailable(): Boolean = available
    override suspend fun supportedLanguages(): Set<VoiceLanguage>? = supported
    override fun listen(language: VoiceLanguage): Flow<SpeechEvent> {
        listenedIn += language
        return script(language)
    }
}
