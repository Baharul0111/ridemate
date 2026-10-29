package app.ridemate.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The in-app map's settings from Remote Config (architecture §4.2 TripInput): `map_enabled` (kill switch, default
 * true) and `map_style_url` (https only; anything else falls back to [DEFAULT_STYLE_URL]). The map is MapLibre
 * Native with OpenFreeMap's free, keyless style: no Google Maps, no key, no billing, never OSM's own tile servers.
 */
data class MapConfig(val enabled: Boolean, val styleUrl: String) {

    companion object {
        /** OpenFreeMap "Liberty": free, no key, OpenStreetMap data (ODbL); attribution stays visible on the map. */
        const val DEFAULT_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

        /** A style that has not loaded within this time shows "Map unavailable · type the address". */
        const val STYLE_LOAD_TIMEOUT_MS: Long = 8_000

        val DEFAULT = MapConfig(enabled = true, styleUrl = DEFAULT_STYLE_URL)
        val DISABLED = MapConfig(enabled = false, styleUrl = DEFAULT_STYLE_URL)

        private val HTTPS_URL = Regex("^https://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._~/-]*)?$")

        /** OSM's own tile servers forbid app traffic like ours (tile usage policy). */
        private const val OSM_TILE_HOST = "tile.openstreetmap.org"

        /** [raw] when it is a plain https URL that is not an OSM tile server, else the default style. */
        fun safeStyleUrl(raw: String?): String {
            val url = raw?.trim().orEmpty()
            return if (HTTPS_URL.matches(url) && !url.contains(OSM_TILE_HOST)) url else DEFAULT_STYLE_URL
        }

        fun from(enabled: Boolean, styleUrl: String?): MapConfig = MapConfig(enabled, safeStyleUrl(styleUrl))
    }
}

/** Why the map is not shown; the trip continues with typed addresses in every case. */
enum class MapUnavailableReason { DISABLED, INIT_FAILED, STYLE_FAILED, TIMEOUT }

/** The outcome of loading the map's style. */
sealed interface MapLoadResult {
    data object Ready : MapLoadResult
    data class Unavailable(val reason: MapUnavailableReason) : MapLoadResult
}

/**
 * Waits for the style to load ([load] returns true when it did, false when MapLibre reported a failure), at most
 * [timeoutMs]. Never throws (except cancellation): a map that cannot load must never block the trip.
 */
suspend fun awaitMapStyle(timeoutMs: Long = MapConfig.STYLE_LOAD_TIMEOUT_MS, load: suspend () -> Boolean): MapLoadResult {
    val loaded = try {
        withTimeoutOrNull(timeoutMs) { load() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
    return when (loaded) {
        true -> MapLoadResult.Ready
        false -> MapLoadResult.Unavailable(MapUnavailableReason.STYLE_FAILED)
        null -> MapLoadResult.Unavailable(MapUnavailableReason.TIMEOUT)
    }
}
