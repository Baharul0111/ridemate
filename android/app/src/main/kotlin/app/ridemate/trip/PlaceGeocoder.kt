package app.ridemate.trip

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Place
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/** The outcome of one address lookup. */
sealed interface GeocodeResult {
    /** The lookup worked; [places] may be empty (no match). */
    data class Found(val places: List<Place>) : GeocodeResult

    /** This phone has no Geocoder backend: addresses are typed and apps open without a prefilled route. */
    data object Unavailable : GeocodeResult

    /** The lookup failed or timed out (usually no network); the typed text can still be used. */
    data object Failed : GeocodeResult
}

/** Address search and reverse lookup (Android's free Geocoder: no key, no map tiles). */
interface PlaceGeocoder {
    val isAvailable: Boolean

    /** Up to [maxResults] matches for [query], preferring ones near [near] when it is known. */
    suspend fun search(query: String, maxResults: Int, near: LatLng?): GeocodeResult

    /** A one-line address for [point], or null when none is known. */
    suspend fun reverse(point: LatLng): String?
}

/**
 * [PlaceGeocoder] over [Geocoder]: the asynchronous `GeocodeListener` API on Android 13+, the blocking API on a
 * background thread below. Searches near a known pickup first (a box of about ±50 km), then anywhere. Every lookup
 * is time-limited ([timeoutMs]); nothing here ever hangs the trip screen.
 */
class AndroidPlaceGeocoder(
    private val context: Context,
    private val locale: () -> Locale = { Locale.getDefault() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val newGeocoder: (Context, Locale) -> Geocoder = ::Geocoder,
) : PlaceGeocoder {

    override val isAvailable: Boolean
        get() = runCatching { Geocoder.isPresent() }.getOrDefault(false)

    override suspend fun search(query: String, maxResults: Int, near: LatLng?): GeocodeResult {
        val text = query.trim()
        if (text.isEmpty()) return GeocodeResult.Found(emptyList())
        if (!isAvailable) return GeocodeResult.Unavailable
        val geocoder = newGeocoder(context, locale())
        // Each half of the time budget: near the pickup first, then anywhere.
        val nearby = near?.let { withTimeoutOrNull(timeoutMs / 2) { byName(geocoder, text, maxResults, boxAround(it)) } }
        val nearbyPlaces = nearby?.let(::toPlaces).orEmpty()
        if (nearbyPlaces.isNotEmpty()) return GeocodeResult.Found(nearbyPlaces.take(maxResults))
        val anywhere = withTimeoutOrNull(timeoutMs / 2) { byName(geocoder, text, maxResults, null) }
            ?: return GeocodeResult.Failed
        return GeocodeResult.Found(toPlaces(anywhere).take(maxResults))
    }

    override suspend fun reverse(point: LatLng): String? {
        if (!isAvailable) return null
        val geocoder = newGeocoder(context, locale())
        val addresses = withTimeoutOrNull(timeoutMs) { byPoint(geocoder, point) } ?: return null
        return addresses.firstNotNullOfOrNull(::labelOf)
    }

    /** Null on failure; an empty list when nothing matched. */
    private suspend fun byName(geocoder: Geocoder, query: String, max: Int, box: Box?): List<Address>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resume(addresses.toList())
                    }

                    override fun onError(errorMessage: String?) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                try {
                    if (box == null) {
                        geocoder.getFromLocationName(query, max, listener)
                    } else {
                        geocoder.getFromLocationName(query, max, box.south, box.west, box.north, box.east, listener)
                    }
                } catch (e: RuntimeException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        } else {
            withContext(io) {
                try {
                    @Suppress("DEPRECATION")
                    if (box == null) {
                        geocoder.getFromLocationName(query, max)
                    } else {
                        geocoder.getFromLocationName(query, max, box.south, box.west, box.north, box.east)
                    }
                } catch (e: IOException) {
                    null
                } catch (e: RuntimeException) {
                    null
                }
            }
        }

    private suspend fun byPoint(geocoder: Geocoder, point: LatLng): List<Address>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (cont.isActive) cont.resume(addresses.toList())
                    }

                    override fun onError(errorMessage: String?) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                try {
                    geocoder.getFromLocation(point.lat, point.lng, 1, listener)
                } catch (e: RuntimeException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        } else {
            withContext(io) {
                try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(point.lat, point.lng, 1)
                } catch (e: IOException) {
                    null
                } catch (e: RuntimeException) {
                    null
                }
            }
        }

    private data class Box(val south: Double, val west: Double, val north: Double, val east: Double)

    private fun boxAround(p: LatLng): Box = Box(
        south = (p.lat - BOX_DEGREES).coerceAtLeast(-90.0),
        west = (p.lng - BOX_DEGREES).coerceAtLeast(-180.0),
        north = (p.lat + BOX_DEGREES).coerceAtMost(90.0),
        east = (p.lng + BOX_DEGREES).coerceAtMost(180.0),
    )

    companion object {
        const val TIMEOUT_MS: Long = 6_000

        /** About 50 km either way at Indian latitudes. */
        private const val BOX_DEGREES = 0.45

        /** Places with coordinates and a label, duplicates (same label) removed, Geocoder order kept. */
        fun toPlaces(addresses: List<Address>): List<Place> = addresses
            .filter { it.hasLatitude() && it.hasLongitude() }
            .mapNotNull { address -> labelOf(address)?.let { Place(it, LatLng(address.latitude, address.longitude)) } }
            .distinctBy { it.label.lowercase(Locale.ROOT) }

        /** The address lines joined, else its named parts; null when the Geocoder gave no text at all. */
        fun labelOf(address: Address): String? {
            val lines = (0..address.maxAddressLineIndex).mapNotNull { address.getAddressLine(it)?.trim() }.filter { it.isNotEmpty() }
            if (lines.isNotEmpty()) return lines.joinToString(", ")
            val parts = listOf(address.featureName, address.subLocality, address.locality, address.adminArea)
                .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()
            return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }
    }
}
