package app.ridemate.trip

import android.content.Context
import androidx.annotation.StringRes
import app.ridemate.R
import app.ridemate.core.format.IstTime
import app.ridemate.net.ApiResult

/** Wording for the trip screen's notices and "what's missing" hints (strings_trip.xml). */
object TripMessages {

    fun noticeText(context: Context, notice: TripNotice): String = when (notice) {
        is TripNotice.Voice -> when (notice.error) {
            SpeechError.NO_MATCH -> context.getString(R.string.trip_voice_no_match)
            SpeechError.LANGUAGE_UNSUPPORTED ->
                context.getString(R.string.trip_voice_language_unsupported, context.getString(notice.language.labelRes))
            SpeechError.NETWORK -> context.getString(R.string.trip_voice_network)
            SpeechError.BUSY -> context.getString(R.string.trip_voice_busy)
            SpeechError.UNAVAILABLE -> context.getString(R.string.trip_voice_unavailable)
            SpeechError.NO_PERMISSION -> context.getString(R.string.trip_mic_denied)
            SpeechError.OTHER -> context.getString(R.string.trip_voice_failed)
        }
        is TripNotice.NothingFoundFor -> context.getString(R.string.trip_nothing_found, notice.text)
        is TripNotice.PickFromList -> pickFromList(context, notice)
        TripNotice.LookupFailed -> context.getString(R.string.trip_lookup_failed)
        TripNotice.NoMapsApp -> context.getString(R.string.trip_no_maps_app)
        TripNotice.MicDenied -> context.getString(R.string.trip_mic_denied)
    }

    private fun pickFromList(context: Context, notice: TripNotice.PickFromList): String {
        if (notice.reason != ChooseReason.AI_UNAVAILABLE) return context.getString(R.string.trip_pick_from_list)
        val resumeAt = (notice.failure as? ApiResult.AiPaused)?.resumeAtIst?.let { IstTime.resumeAtIst(it) }
        return if (resumeAt != null) {
            context.getString(R.string.trip_pick_ai_paused, resumeAt)
        } else {
            context.getString(R.string.trip_pick_ai_unavailable)
        }
    }

    /** Why Compare is disabled, so the user knows what to do next; null when it is enabled (or a check runs). */
    @StringRes
    fun missingHint(state: TripUiState): Int? = when {
        state.canCompare || state.checking -> null
        state.pickupPlace == null -> R.string.trip_need_pickup
        state.pending != null -> R.string.trip_need_confirm
        state.drop == null -> R.string.trip_need_drop
        state.selectedApps.isEmpty() && !(state.includeOndc && state.ondcSelectable) -> R.string.trip_need_app
        state.vehicles.isEmpty() -> R.string.trip_need_vehicle
        else -> null
    }

    @StringRes
    fun vehicleLabel(vehicle: VehicleChoice): Int = when (vehicle) {
        VehicleChoice.BIKE -> R.string.trip_vehicle_bike
        VehicleChoice.AUTO -> R.string.trip_vehicle_auto
        VehicleChoice.CAB -> R.string.trip_vehicle_cab
    }
}
