package app.ridemate.config

import android.content.res.AssetManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Last fetch outcome of Remote Config. */
enum class FetchStatus { NEVER, SUCCESS, FAILURE, THROTTLED }

data class BackendInfo(val lastFetchUtcMs: Long?, val status: FetchStatus)

/** The Firebase Remote Config seam (production: [FirebaseRemoteConfigBackend]; tests: a fake). */
interface RemoteConfigBackend {
    /** Applies the fetch settings; returns once they are in effect. */
    suspend fun configure(fetchTimeoutSeconds: Long, minimumFetchIntervalSeconds: Long)

    /** Fetches and activates; throws on failure. Bounded by the configured fetch timeout. */
    suspend fun fetchAndActivate(): Boolean

    suspend fun activate(): Boolean

    /** The active value of [key] if it came from the server, else null (defaults are ours, not Firebase's). */
    fun remoteValue(key: String): String?

    /** Keys that currently have an active server value. */
    fun remoteKeys(): Set<String>

    /** Real-time updates (`addOnConfigUpdateListener`): [onUpdate] receives the changed keys (not yet active). */
    fun addUpdateListener(onUpdate: (Set<String>) -> Unit): AutoCloseable

    fun info(): BackendInfo
}

/** Bundled defaults shipped in the APK. */
fun interface BundledConfigSource {
    /** key → raw string value. */
    fun load(): Map<String, String>
}

/**
 * Reads `assets/config/<key>.json`: every `.json` file is the bundled value of the key named like the file
 * (other files, such as a README, are ignored). A file holding a JSON string literal (`"https://…"`) yields the
 * string's content; anything else (objects, numbers, booleans) yields the trimmed text as is.
 */
class AssetBundledConfig(private val assets: AssetManager, private val dir: String = "config") : BundledConfigSource {
    override fun load(): Map<String, String> {
        val names = runCatching { assets.list(dir) }.getOrNull().orEmpty()
        return names.filter { it.endsWith(".json") }.mapNotNull { name ->
            val text = runCatching { assets.open("$dir/$name").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
            text?.let { name.removeSuffix(".json") to decodeBundledValue(it) }
        }.toMap()
    }

    companion object {
        fun decodeBundledValue(text: String): String {
            val trimmed = text.removePrefix("\uFEFF").trim()
            val element = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
            return if (element is JsonPrimitive && element.isString) element.content else trimmed
        }
    }
}
