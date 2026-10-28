package app.ridemate.core.watch

import app.ridemate.core.config.CompiledRedactedTemplate
import app.ridemate.core.config.CompiledTemplate
import app.ridemate.core.config.NotificationEvent
import app.ridemate.core.config.NotificationTemplateEntry

/** A deterministic template match. Values are parsed from the ride app's own post (D8: "from <App> notification"). */
data class TemplateHit(
    val templateId: String,
    val event: NotificationEvent,
    /** True when a `redacted` structural template matched a post without text. */
    val structural: Boolean,
    val etaMin: Int? = null,
    val distanceMeters: Int? = null,
    /** SHA-256 of the normalised plate; the raw plate never leaves [TemplateEngine.match]. */
    val plateHash: String? = null,
)

/** The notification id and tag of a post, used by `id_changed` structural templates. */
data class IdTag(val id: Int, val tag: String?)

/** Runs RE2/J templates (linear time, so a hostile post cannot stall the listener). First matching template wins. */
object TemplateEngine {
    private const val MAX_ETA_MIN = 180
    private val DISTANCE_UNITS_KM = setOf("km", "kms", "किमी", "कि.मी.", "कि.मी")
    private val DISTANCE_UNITS_M = setOf("m", "mtr", "mtrs", "meter", "meters", "metre", "metres", "मीटर")

    fun match(entry: NotificationTemplateEntry, post: PostSnapshot, previousIdTag: IdTag?): TemplateHit? =
        if (post.isRedacted) matchStructural(entry.redacted, post, previousIdTag) else matchText(entry.templates, post)

    private fun matchText(templates: List<CompiledTemplate>, post: PostSnapshot): TemplateHit? {
        val text = post.matchText
        for (template in templates) {
            if (template.spec.channelId != null && template.spec.channelId != post.channelId) continue
            if (template.spec.ongoing != null && template.spec.ongoing != post.isOngoing) continue
            val matcher = template.regex.matcher(text)
            if (!matcher.find()) continue
            fun group(name: String): String? = if (name in template.groupNames) matcher.group(name) else null
            return TemplateHit(
                templateId = template.id,
                event = template.event,
                structural = false,
                etaMin = group("eta")?.let(::parseMinutes),
                distanceMeters = group("dist")?.let(::parseDistanceMeters),
                plateHash = group("plate")?.let(PlateHasher::hash),
            )
        }
        return null
    }

    private fun matchStructural(
        templates: List<CompiledRedactedTemplate>,
        post: PostSnapshot,
        previousIdTag: IdTag?,
    ): TemplateHit? {
        val current = IdTag(post.id, post.tag)
        for (template in templates) {
            val spec = template.spec
            if (spec.channelId != null && spec.channelId != post.channelId) continue
            if (spec.ongoing != null && spec.ongoing != post.isOngoing) continue
            if (spec.category != null && spec.category != post.category) continue
            if (spec.notificationId != null && spec.notificationId != post.id) continue
            if (spec.tag != null && spec.tag != post.tag) continue
            if (spec.idChanged == true && (previousIdTag == null || previousIdTag == current)) continue
            return TemplateHit(templateId = template.id, event = spec.event, structural = true)
        }
        return null
    }

    /** Minutes from a captured group, accepting any Unicode digits (Devanagari included). */
    fun parseMinutes(raw: String): Int? {
        val digits = asciiDigits(raw)
        val value = digits.toIntOrNull() ?: return null
        return value.takeIf { it in 0..MAX_ETA_MIN }
    }

    /** `1.2 km` → 1200, `800 m` → 800. A bare number has no unit and is not trusted. */
    fun parseDistanceMeters(raw: String): Int? {
        val trimmed = raw.trim()
        var end = 0
        val number = StringBuilder()
        while (end < trimmed.length) {
            val c = trimmed[end]
            when {
                Character.isDigit(c) -> number.append(Character.forDigit(Character.digit(c, 10), 10))
                c == '.' -> number.append('.')
                else -> break
            }
            end++
        }
        val value = number.toString().toDoubleOrNull() ?: return null
        val unit = trimmed.substring(end).trim().lowercase()
        val meters = when (unit) {
            in DISTANCE_UNITS_KM -> value * 1000
            in DISTANCE_UNITS_M -> value
            else -> return null
        }
        return meters.toInt().takeIf { it in 0..200_000 }
    }

    private fun asciiDigits(raw: String): String = buildString {
        for (c in raw) if (Character.isDigit(c)) append(Character.forDigit(Character.digit(c, 10), 10))
    }
}
