package app.ridemate.trip

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import java.util.Locale

/**
 * "View on map" (goal 1): a `geo:` intent for the phone's own maps app. RideMate shows no map tiles and needs no
 * maps key.
 */
object MapIntents {

    /** `geo:lat,lng?q=lat,lng(label)` with a pin, or `geo:0,0?q=<address>` for a typed address without coordinates. */
    fun geoUri(place: TripPlace): String {
        val point = place.point ?: return "geo:0,0?q=" + Uri.encode(place.label)
        val coords = String.format(Locale.ROOT, "%.6f,%.6f", point.lat, point.lng)
        return "geo:$coords?q=$coords(" + Uri.encode(place.label) + ")"
    }

    fun viewOnMap(place: TripPlace): Intent = Intent(Intent.ACTION_VIEW, geoUri(place).toUri())
}
