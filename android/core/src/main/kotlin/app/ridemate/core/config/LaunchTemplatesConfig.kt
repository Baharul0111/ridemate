package app.ridemate.core.config

import app.ridemate.core.model.Place
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.util.Locale

/** Remote Config `launch_templates` (contracts §2.6): how AppLauncher opens each ride app with the route. */
@Serializable
data class LaunchTemplatesConfig(
    val schema: String,
    val apps: Map<String, LaunchTemplate> = emptyMap(),
) {
    fun templateFor(packageName: String): LaunchTemplate? = apps[packageName]
}

@Serializable
enum class LaunchMode {
    /** Open a deep link with pickup and drop filled in. */
    @SerialName("uri") URI,

    /** Open the app and offer the drop address on the clipboard ("Paste drop" chip). */
    @SerialName("launch_paste") LAUNCH_PASTE,

    /** Just open the app. */
    @SerialName("launch") LAUNCH,
}

@Serializable
data class LaunchTemplate(
    val mode: LaunchMode,
    val uri: String? = null,
    val fallback: LaunchMode? = null,
) {
    /**
     * The deep link with every placeholder filled: coordinates with six decimals, names URL-encoded (spaces as
     * `%20`). Null for templates that don't use a URI.
     */
    fun buildUri(pickup: Place, drop: Place): String? {
        if (mode != LaunchMode.URI || uri == null) return null
        val values = mapOf(
            "plat" to coordinate(pickup.point.lat), "plng" to coordinate(pickup.point.lng), "pname" to encode(pickup.label),
            "dlat" to coordinate(drop.point.lat), "dlng" to coordinate(drop.point.lng), "dname" to encode(drop.label),
        )
        return PLACEHOLDER.replace(uri) { values.getValue(it.groupValues[1]) }
    }

    internal fun validate(app: String) {
        if (mode == LaunchMode.URI) {
            val template = requireNotNull(uri) { "$app: uri mode needs a uri" }
            require(template.startsWith("https://")) { "$app: uri must be https" }
            val unknown = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.filterNot { it in PLACEHOLDERS }.toList()
            require(unknown.isEmpty()) { "$app: unknown placeholders $unknown" }
        }
        require(fallback != LaunchMode.URI) { "$app: fallback cannot be another uri" }
    }
}

private val PLACEHOLDER = Regex("\\{([a-z]+)}")
private val PLACEHOLDERS = setOf("plat", "plng", "pname", "dlat", "dlng", "dname")

private fun coordinate(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

// The charset-name overload: URLEncoder.encode(String, Charset) needs Android API 33.
private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/**
 * Parses and validates `launch_templates`; throws on a wrong schema major or a malformed template.
 *
 * A `mode` this build doesn't know (an additive MINOR value such as the bundled defaults' `"demo"`) leaves only that
 * app out, so AppLauncher plain-opens it, instead of rejecting every app's template; an unknown `fallback` is dropped
 * the same way. Everything else about an entry is still validated strictly.
 */
fun parseLaunchTemplates(raw: String): LaunchTemplatesConfig {
    val payload = fareSidePayload(raw)
    val apps = payload["apps"] as? JsonObject
    val known = if (apps == null) payload else JsonObject(payload + ("apps" to knownModesOnly(apps)))
    return ConfigJson.decodeFromJsonElement(LaunchTemplatesConfig.serializer(), known)
        .also { cfg -> cfg.apps.forEach { (app, t) -> t.validate(app) } }
}

private val MODE_NAMES: Set<String> = LaunchMode.serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName).toSet() }

private fun knownModesOnly(apps: JsonObject): JsonObject = JsonObject(
    apps.mapNotNull { (app, entry) ->
        val template = entry as? JsonObject ?: return@mapNotNull app to entry
        if (template.modeName("mode")?.let { it !in MODE_NAMES } == true) return@mapNotNull null
        val fallbackUnknown = template.modeName("fallback")?.let { it !in MODE_NAMES } == true
        app to if (fallbackUnknown) JsonObject(template - "fallback") else template
    }.toMap(),
)

private fun JsonObject.modeName(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
