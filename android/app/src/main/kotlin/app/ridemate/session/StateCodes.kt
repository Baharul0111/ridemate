package app.ridemate.session

import android.content.Context
import android.location.Geocoder
import app.ridemate.core.model.LatLng
import java.util.Locale

/** The Indian state of a point, as its ISO 3166-2:IN code without "IN-" ("AS" for Assam); null when unknown. */
fun interface StateCodeResolver {
    /** Blocking (Geocoder may use the network): call off the main and guardian threads. */
    fun stateCodeAt(point: LatLng): String?

    companion object {
        val NONE = StateCodeResolver { null }
    }
}

/**
 * [StateCodeResolver] over Android's Geocoder (the pickup's `adminArea`). Any failure (no geocoder service, offline,
 * an unknown name) gives null, and the fee rules then use their defaults: the note still says "may apply, up to".
 */
class GeocoderStateCodes(private val context: Context) : StateCodeResolver {
    override fun stateCodeAt(point: LatLng): String? = runCatching {
        if (!Geocoder.isPresent()) return@runCatching null
        @Suppress("DEPRECATION")
        val address = Geocoder(context, Locale.ENGLISH).getFromLocation(point.lat, point.lng, 1)?.firstOrNull()
        IndianStateCodes.codeOf(address?.adminArea)
    }.getOrNull()
}

/** Indian state and union territory names (English, as Geocoder gives them) → ISO 3166-2:IN codes. */
object IndianStateCodes {
    private val BY_NAME: Map<String, String> = mapOf(
        "andhra pradesh" to "AP", "arunachal pradesh" to "AR", "assam" to "AS", "bihar" to "BR",
        "chhattisgarh" to "CT", "goa" to "GA", "gujarat" to "GJ", "haryana" to "HR", "himachal pradesh" to "HP",
        "jharkhand" to "JH", "karnataka" to "KA", "kerala" to "KL", "madhya pradesh" to "MP", "maharashtra" to "MH",
        "manipur" to "MN", "meghalaya" to "ML", "mizoram" to "MZ", "nagaland" to "NL", "odisha" to "OR",
        "punjab" to "PB", "rajasthan" to "RJ", "sikkim" to "SK", "tamil nadu" to "TN", "telangana" to "TG",
        "tripura" to "TR", "uttar pradesh" to "UP", "uttarakhand" to "UT", "west bengal" to "WB",
        "andaman and nicobar islands" to "AN", "chandigarh" to "CH",
        "dadra and nagar haveli and daman and diu" to "DH", "delhi" to "DL", "national capital territory of delhi" to "DL",
        "jammu and kashmir" to "JK", "ladakh" to "LA", "lakshadweep" to "LD", "puducherry" to "PY",
    )

    fun codeOf(adminArea: String?): String? {
        val key = adminArea?.trim()?.lowercase(Locale.ROOT)?.replace("&", "and")?.replace(Regex("\\s+"), " ") ?: return null
        return BY_NAME[key]
    }
}
