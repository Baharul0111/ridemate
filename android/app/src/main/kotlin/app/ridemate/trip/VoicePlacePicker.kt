package app.ridemate.trip

import app.ridemate.core.model.LatLng
import app.ridemate.net.ApiClient
import app.ridemate.net.ApiResult
import app.ridemate.net.PlaceCandidate
import app.ridemate.net.PlacePickRequest
import kotlinx.coroutines.withTimeoutOrNull

/** What the voice destination flow produced. Nothing is used until the user confirms the address text. */
sealed interface PlacePickOutcome {
    /** Spark Open picked [pick] among [candidates]: shown for confirmation, with the others still offered. */
    data class Picked(val pick: PlaceSuggestion, val candidates: List<PlaceSuggestion>) : PlacePickOutcome

    /** The user picks from [candidates] ([reason] says why the model did not). */
    data class Choose(val candidates: List<PlaceSuggestion>, val reason: ChooseReason, val failure: ApiResult<*>?) : PlacePickOutcome

    /** The Geocoder found nothing for the transcript. */
    data object NoCandidates : PlacePickOutcome

    /** No Geocoder on this phone: the transcript can only be used as typed text. */
    data object GeocoderUnavailable : PlacePickOutcome

    /** The Geocoder lookup failed (usually offline). */
    data object LookupFailed : PlacePickOutcome
}

enum class ChooseReason {
    /** The model answered `none`. */
    MODEL_NONE,

    /** AI place-pick was unavailable (signed out, paused, offline, timeout, …): manual choice. */
    AI_UNAVAILABLE,

    /** Only one candidate: no model call needed, the user still confirms it. */
    SINGLE_CANDIDATE,
}

/**
 * Voice destination (architecture §4.2 TripInput, §4.10 "Place pick"): transcript → Geocoder (≤ 5 candidates) →
 * `/v1/place-pick` (Muse Spark Open picks a candidate id or `none`). The model's answer is used only as an enum over
 * RideMate's own candidate ids (I5): the label shown is the Geocoder's text, and an id that is not one of the
 * candidates counts as `none`. Every failure falls back to the user choosing from the list. Time-limited.
 */
class VoicePlacePicker(
    private val geocoder: PlaceGeocoder,
    private val api: ApiClient,
    private val maxCandidates: Int = PlaceSearchController.MAX_RESULTS,
    private val pickTimeoutMs: Long = PICK_TIMEOUT_MS,
) {
    suspend fun pick(transcript: String, language: VoiceLanguage, near: LatLng?, sessionId: String? = null): PlacePickOutcome {
        val text = transcript.trim()
        if (text.isEmpty()) return PlacePickOutcome.NoCandidates
        val candidates = when (val found = geocoder.search(text, maxCandidates, near)) {
            is GeocodeResult.Found -> found.places.take(maxCandidates).mapIndexed { i, p -> PlaceSuggestion("c${i + 1}", p.label, p.point) }
            GeocodeResult.Unavailable -> return PlacePickOutcome.GeocoderUnavailable
            GeocodeResult.Failed -> return PlacePickOutcome.LookupFailed
        }
        if (candidates.isEmpty()) return PlacePickOutcome.NoCandidates
        if (candidates.size == 1) return PlacePickOutcome.Choose(candidates, ChooseReason.SINGLE_CANDIDATE, null)

        val request = PlacePickRequest(text, language.tag, candidates.map { PlaceCandidate(it.id, it.label) })
        val result = withTimeoutOrNull(pickTimeoutMs) { api.placePick(request, sessionId) } ?: ApiResult.Timeout
        return when (result) {
            is ApiResult.Ok -> {
                val pick = result.body.pick?.let { id -> candidates.firstOrNull { it.id == id } }
                if (pick != null) PlacePickOutcome.Picked(pick, candidates) else PlacePickOutcome.Choose(candidates, ChooseReason.MODEL_NONE, null)
            }
            else -> PlacePickOutcome.Choose(candidates, ChooseReason.AI_UNAVAILABLE, result)
        }
    }

    companion object {
        /** The phone deadline for place-pick is 11 s (server 6 s + 5 s); this guard sits just above it. */
        const val PICK_TIMEOUT_MS: Long = 12_000
    }
}
