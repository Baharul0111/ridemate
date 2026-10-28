package app.ridemate.core.config

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

/** Remote Config `notification_templates` (contracts §2.1). */

/** What a ride-app notification template says happened. */
@Serializable
enum class NotificationEvent {
    SEARCHING, ASSIGNED, ARRIVING, DRIVER_CANCELLED, NO_DRIVERS, CANCELLED, ENDED, TRIP_STARTED,
}

@Serializable
data class NotificationTemplateSpec(
    val id: String,
    val event: NotificationEvent,
    /** RE2 syntax, matched against `title + "\n" + text`. Optional named groups: `eta`, `dist`, `plate`. */
    val pattern: String,
    @SerialName("channel_id") val channelId: String? = null,
    val ongoing: Boolean? = null,
)

/**
 * Structural match for a redacted or empty post (Android 15 OTP redaction). Every non-null field must match.
 * `id`, `tag`, `notification_id` and `id_changed` are additive extensions: `id_changed = true` matches only when the
 * post's (notification id, tag) differs from the previous post seen from the same package.
 */
@Serializable
data class RedactedTemplateSpec(
    val event: NotificationEvent,
    @SerialName("channel_id") val channelId: String? = null,
    val ongoing: Boolean? = null,
    val category: String? = null,
    val id: String? = null,
    val tag: String? = null,
    @SerialName("notification_id") val notificationId: Int? = null,
    @SerialName("id_changed") val idChanged: Boolean? = null,
)

class CompiledTemplate(val spec: NotificationTemplateSpec, val regex: Pattern) {
    val id: String get() = spec.id
    val event: NotificationEvent get() = spec.event
    val groupNames: Set<String> = regex.namedGroups().keys
}

class CompiledRedactedTemplate(val id: String, val spec: RedactedTemplateSpec)

class NotificationTemplateEntry(
    override val minVersionCode: Long,
    override val maxKnownVersionCode: Long?,
    val templates: List<CompiledTemplate>,
    val redacted: List<CompiledRedactedTemplate>,
    val cancelledKeywords: List<String>,
    val ongoingChannelIds: List<String>,
) : VersionedEntry

enum class TemplateRejectReason { BAD_PATTERN, MALFORMED, DUPLICATE_ID }

data class RejectedTemplate(
    val packageName: String,
    val minVersionCode: Long,
    val templateId: String?,
    val reason: TemplateRejectReason,
)

class NotificationTemplatesConfig(
    val schema: String,
    val apps: Map<String, List<NotificationTemplateEntry>>,
    /** Templates dropped at load; the rest of the payload is kept. Logged by the app (ids only). */
    val rejected: List<RejectedTemplate>,
) {
    /** §4.5.7: newest entry ≤ the installed versionCode; an unknown or newer version is flagged as a fallback. */
    fun entryFor(packageName: String, versionCode: Long?): VersionPick<NotificationTemplateEntry>? =
        apps[packageName]?.let { pickForVersion(it, versionCode) }

    companion object {
        val EMPTY = NotificationTemplatesConfig("1.0", emptyMap(), emptyList())

        /**
         * Parses and compiles a payload. Throws on a malformed payload or an unsupported schema major, so it can be
         * used with [resolveLayered]; a single bad template (bad RE2/J pattern, unknown event, duplicate id) is
         * rejected and recorded while the rest of the payload is kept.
         */
        fun parse(raw: String): NotificationTemplatesConfig {
            val root = ConfigJson.parseToJsonElement(raw) as? JsonObject
                ?: throw IllegalArgumentException("payload is not an object")
            require(schemaMajor(root) == SupportedSchemas.MAJOR) { "unsupported schema" }
            val schema = root.getValue("schema").jsonPrimitive.content
            val appsJson = root["apps"] as? JsonObject ?: JsonObject(emptyMap())
            val rejected = mutableListOf<RejectedTemplate>()
            val apps = appsJson.mapValues { (pkg, entries) ->
                require(entries is JsonArray) { "apps.$pkg must be a list" }
                entries.map { parseEntry(pkg, it as JsonObject, rejected) }
            }
            return NotificationTemplatesConfig(schema, apps, rejected)
        }

        private fun parseEntry(pkg: String, obj: JsonObject, rejected: MutableList<RejectedTemplate>): NotificationTemplateEntry {
            val header = ConfigJson.decodeFromJsonElement<EntryHeader>(obj)
            val seenIds = HashSet<String>()
            val templates = (obj["templates"] as? JsonArray).orEmpty().mapNotNull { element ->
                compileTemplate(element)
                    .onFailure { reason ->
                        rejected += RejectedTemplate(pkg, header.minVersionCode, templateIdOf(element), reason)
                    }
                    .valueOrNull
                    ?.takeIf { compiled ->
                        seenIds.add(compiled.id).also { unique ->
                            if (!unique) {
                                rejected += RejectedTemplate(pkg, header.minVersionCode, compiled.id, TemplateRejectReason.DUPLICATE_ID)
                            }
                        }
                    }
            }
            val redacted = (obj["redacted"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
                runCatching { ConfigJson.decodeFromJsonElement<RedactedTemplateSpec>(element) }
                    .map { spec -> CompiledRedactedTemplate(spec.id ?: "$pkg.redacted.$index", spec) }
                    .onFailure { rejected += RejectedTemplate(pkg, header.minVersionCode, null, TemplateRejectReason.MALFORMED) }
                    .getOrNull()
            }
            return NotificationTemplateEntry(
                minVersionCode = header.minVersionCode,
                maxKnownVersionCode = header.maxKnownVersionCode,
                templates = templates,
                redacted = redacted,
                cancelledKeywords = header.cancelledKeywords,
                ongoingChannelIds = header.ongoingChannelIds,
            )
        }

        private fun compileTemplate(element: JsonElement): Outcome<CompiledTemplate> {
            val spec = runCatching { ConfigJson.decodeFromJsonElement<NotificationTemplateSpec>(element) }
                .getOrElse { return Outcome.Failed(TemplateRejectReason.MALFORMED) }
            val regex = try {
                Pattern.compile(spec.pattern)
            } catch (_: PatternSyntaxException) {
                return Outcome.Failed(TemplateRejectReason.BAD_PATTERN)
            }
            return Outcome.Ok(CompiledTemplate(spec, regex))
        }

        private fun templateIdOf(element: JsonElement): String? =
            runCatching { (element as JsonObject)["id"]?.jsonPrimitive?.content }.getOrNull()
    }
}

@Serializable
private data class EntryHeader(
    @SerialName("min_version_code") val minVersionCode: Long,
    @SerialName("max_known_version_code") val maxKnownVersionCode: Long? = null,
    @SerialName("cancelled_keywords") val cancelledKeywords: List<String> = emptyList(),
    @SerialName("ongoing_channel_ids") val ongoingChannelIds: List<String> = emptyList(),
)

private sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Failed(val reason: TemplateRejectReason) : Outcome<Nothing>

    val valueOrNull: T? get() = (this as? Ok<T>)?.value

    fun onFailure(block: (TemplateRejectReason) -> Unit): Outcome<T> {
        if (this is Failed) block(reason)
        return this
    }
}
