package app.ridemate.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The JSON settings every Remote Config payload is parsed with: unknown keys are tolerated (additive changes). */
val ConfigJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = false
}

/** Major version of each Remote Config payload this build understands (contracts §2). */
object SupportedSchemas {
    const val MAJOR = 1
}

/** Reads `"schema": "MAJOR.MINOR"` from a payload; null when missing or malformed. */
fun schemaMajor(obj: JsonObject): Int? =
    obj["schema"]?.jsonPrimitive?.content?.substringBefore('.')?.toIntOrNull()

/**
 * Picks the remote payload if it parses and its schema major matches, else the last-good cache, else the bundled default.
 * [parse] must throw on any malformed input; the result records which layer won so it can be logged.
 */
fun <T> resolveLayered(
    remote: String?,
    lastGood: String?,
    bundled: String,
    parse: (String) -> T,
): Layered<T> {
    for ((source, raw) in listOf(Layer.REMOTE to remote, Layer.LAST_GOOD to lastGood)) {
        if (raw.isNullOrBlank()) continue
        val parsed = runCatching {
            val obj = ConfigJson.parseToJsonElement(raw) as JsonObject
            require(schemaMajor(obj) == SupportedSchemas.MAJOR) { "unsupported schema" }
            parse(raw)
        }.getOrNull()
        if (parsed != null) return Layered(parsed, source, raw)
    }
    return Layered(parse(bundled), Layer.BUNDLED, bundled)
}

enum class Layer { REMOTE, LAST_GOOD, BUNDLED }

data class Layered<T>(val value: T, val source: Layer, val raw: String)

/**
 * A per-app list of entries keyed by `min_version_code`: the newest entry ≤ the installed versionCode wins (§4.5.7).
 * `max_known_version_code` (optional) is the newest app version the entry was verified against; anything newer is
 * still served by that entry but flagged as a fallback so it can be logged (`template=fallback`).
 */
interface VersionedEntry {
    val minVersionCode: Long
    val maxKnownVersionCode: Long? get() = null
}

data class VersionPick<T>(val entry: T, val isFallback: Boolean)

fun <T : VersionedEntry> pickForVersion(entries: List<T>, installedVersionCode: Long?): VersionPick<T>? {
    if (entries.isEmpty()) return null
    val sorted = entries.sortedByDescending { it.minVersionCode }
    if (installedVersionCode == null) return VersionPick(sorted.first(), isFallback = true)
    val match = sorted.firstOrNull { it.minVersionCode <= installedVersionCode }
        ?: return VersionPick(sorted.last(), isFallback = true)
    val beyondKnown = match.maxKnownVersionCode?.let { installedVersionCode > it } ?: false
    return VersionPick(match, isFallback = beyondKnown)
}
