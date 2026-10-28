package app.ridemate.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Remote Config `cancel_cards` (contracts §2.4): each app's cancel steps for the guided cancel chain (§4.7). */
@Serializable
data class CancelCardsConfig(
    val schema: String,
    val apps: Map<String, CancelCard> = emptyMap(),
) {
    fun cardFor(packageName: String): CancelCard? = apps[packageName]
}

@Serializable
data class CancelCard(
    /** Steps per language code (`en`, `hi`); `en` is required and is the fallback. */
    val steps: Map<String, List<String>>,
    @SerialName("point_target") val pointTarget: String? = null,
    @SerialName("cancelled_keywords") val cancelledKeywords: List<String> = emptyList(),
) {
    /** Steps in [language] (falling back to English), split into plain and `**bold**` segments. */
    fun stepsFor(language: String): List<CancelStep> =
        (steps[language]?.takeIf { it.isNotEmpty() } ?: steps.getValue(ENGLISH)).map(CancelStep::parse)

    internal fun validate(app: String) {
        require(!steps[ENGLISH].isNullOrEmpty()) { "$app: English steps are required" }
        steps.forEach { (lang, list) ->
            list.forEachIndexed { i, step ->
                require(step.isNotBlank()) { "$app.steps.$lang[$i] is blank" }
                require(step.split(BOLD).size % 2 == 1) { "$app.steps.$lang[$i] has an unclosed **" }
            }
        }
        require(cancelledKeywords.none { it.isBlank() }) { "$app: blank cancelled keyword" }
    }
}

private const val ENGLISH = "en"

/** One instruction line, e.g. "Tap **Cancel ride**" → [plain "Tap ", bold "Cancel ride"]. */
data class CancelStep(val segments: List<Segment>) {
    data class Segment(val text: String, val bold: Boolean)

    val plainText: String get() = segments.joinToString("") { it.text }

    companion object {
        fun parse(step: String): CancelStep = CancelStep(
            step.split(BOLD).mapIndexedNotNull { i, part -> part.takeIf { it.isNotEmpty() }?.let { Segment(it, i % 2 == 1) } },
        )
    }
}

private const val BOLD = "**"

/** Parses and validates `cancel_cards`; throws on a wrong schema major or a card without English steps. */
fun parseCancelCards(raw: String): CancelCardsConfig =
    decodeFareSidePayload(raw, CancelCardsConfig.serializer()).also { cfg -> cfg.apps.forEach { (app, card) -> card.validate(app) } }
