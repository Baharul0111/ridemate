package app.ridemate.trip

import android.content.SharedPreferences
import androidx.core.content.edit
import app.ridemate.core.model.LatLng
import app.ridemate.util.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Destinations the user confirmed recently, kept **only on this phone** and only as long as ride data is
 * ([retentionMs], 24 h like sessions, §5 / goal 24): older entries are dropped on every read and write. Erase
 * wipes them ([clear], registered as a LocalDataWiper in AppGraph). Newest first, at most [maxEntries], no
 * duplicates (same label, any case).
 */
class RecentPlacesStore(
    private val prefs: SharedPreferences,
    private val clock: Clock,
    private val retentionMs: Long = RETENTION_MS,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    @Serializable
    private data class Entry(val label: String, val lat: Double? = null, val lng: Double? = null, val savedAtUtc: Long)

    /** The recent places, newest first (expired ones are purged first). */
    @Synchronized
    fun recent(): List<TripPlace> {
        val all = read()
        val fresh = fresh(all)
        if (fresh.size != all.size) write(fresh)
        return fresh.map { it.toPlace() }
    }

    /** Remembers [place] as the newest entry. */
    @Synchronized
    fun add(place: TripPlace) {
        val entry = Entry(place.label.trim(), place.point?.lat, place.point?.lng, clock.wallMs())
        val kept = fresh().filterNot { it.label.equals(entry.label, ignoreCase = true) }
        write((listOf(entry) + kept).take(maxEntries))
    }

    /** Drops expired entries (run with the 24 h ride-data purge at start-up); returns how many were removed. */
    @Synchronized
    fun purgeExpired(): Int {
        val all = read()
        val fresh = fresh(all)
        write(fresh) // also drops a value that no longer decodes
        return all.size - fresh.size
    }

    @Synchronized
    fun clear() {
        prefs.edit { remove(KEY) }
    }

    private fun fresh(entries: List<Entry> = read()): List<Entry> {
        val cutoff = clock.wallMs() - retentionMs
        return entries.filter { it.savedAtUtc in cutoff..clock.wallMs() && it.label.isNotBlank() }
    }

    private fun read(): List<Entry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { JSON.decodeFromString(SERIALIZER, raw) }.getOrDefault(emptyList())
    }

    private fun write(entries: List<Entry>) {
        prefs.edit {
            if (entries.isEmpty()) remove(KEY) else putString(KEY, JSON.encodeToString(SERIALIZER, entries))
        }
    }

    private fun Entry.toPlace(): TripPlace =
        TripPlace(label, if (lat != null && lng != null) LatLng(lat, lng) else null)

    companion object {
        const val PREFS_NAME = "ridemate_recent_places"
        const val RETENTION_MS: Long = 24L * 60 * 60 * 1000
        const val MAX_ENTRIES: Int = 6
        private const val KEY = "recent"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val SERIALIZER = ListSerializer(Entry.serializer())
    }
}
