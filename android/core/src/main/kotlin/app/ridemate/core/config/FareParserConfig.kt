package app.ridemate.core.config

import app.ridemate.core.model.ScreenType
import app.ridemate.core.model.VehicleClass
import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Remote Config `fare_parsers` (contracts §2.2): per-app parser rules, versioned by the app's versionCode. */
@Serializable
data class FareParsersConfig(
    val schema: String,
    val apps: Map<String, List<FareParserEntry>> = emptyMap(),
) {
    /** The entry for [packageName] at [installedVersionCode] (§4.5.7 version pick), or null if the app has none. */
    fun entryFor(packageName: String, installedVersionCode: Long?): VersionPick<FareParserEntry>? =
        apps[packageName]?.let { pickForVersion(it, installedVersionCode) }
}

@Serializable
data class VehicleLabelRule(
    val pattern: String,
    @SerialName("vehicle_class") val vehicleClass: VehicleClass,
)

@Serializable
data class FareParserEntry(
    @SerialName("min_version_code") override val minVersionCode: Long = 0,
    @SerialName("max_known_version_code") override val maxKnownVersionCode: Long? = null,
    @SerialName("vehicle_labels") val vehicleLabels: List<VehicleLabelRule>,
    @SerialName("price_token_pattern") val priceTokenPattern: String,
    @SerialName("eta_pattern") val etaPattern: String,
    @SerialName("strike_height_ratio") val strikeHeightRatio: Double = 0.8,
    @SerialName("row_gap_ratio") val rowGapRatio: Double = 1.8,
    @SerialName("driver_eta_anchors") val driverEtaAnchors: List<String> = emptyList(),
    @SerialName("driver_distance_pattern") val driverDistancePattern: String,
    @SerialName("screen_keywords") val screenKeywords: Map<String, List<String>> = emptyMap(),
) : VersionedEntry {
    /** Keywords per screen type; keys this build doesn't know are ignored (additive MINOR changes). */
    val keywordsByScreen: Map<ScreenType, List<String>>
        get() = screenKeywords.mapNotNull { (key, words) -> SCREEN_BY_WIRE[key]?.let { it to words } }.toMap()

    internal fun validate(app: String) {
        require(minVersionCode >= 0) { "$app: min_version_code must be ≥ 0" }
        require(vehicleLabels.isNotEmpty()) { "$app: vehicle_labels is empty" }
        vehicleLabels.forEachIndexed { i, rule -> compileFareRegex(rule.pattern, "$app.vehicle_labels[$i]") }
        compileFareRegex(priceTokenPattern, "$app.price_token_pattern")
        compileFareRegex(etaPattern, "$app.eta_pattern")
        compileFareRegex(driverDistancePattern, "$app.driver_distance_pattern")
        require(strikeHeightRatio > 0 && strikeHeightRatio <= 1) { "$app: strike_height_ratio must be in (0, 1]" }
        require(rowGapRatio > 0 && rowGapRatio <= 10) { "$app: row_gap_ratio must be in (0, 10]" }
        require(driverEtaAnchors.none { it.isBlank() }) { "$app: blank driver_eta_anchor" }
        require(screenKeywords.values.flatten().none { it.isBlank() }) { "$app: blank screen keyword" }
    }
}

/**
 * Parses and validates `fare_parsers`; throws on a wrong schema major, a malformed payload, or any pattern that
 * RE2/J cannot compile (the whole payload is rejected, so [resolveLayered] falls back to the last good one).
 */
fun parseFareParsers(raw: String): FareParsersConfig {
    val config = decodeFareSidePayload(raw, FareParsersConfig.serializer())
    config.apps.forEach { (app, entries) ->
        require(entries.isNotEmpty()) { "$app: no parser entries" }
        entries.forEach { it.validate(app) }
    }
    return config
}

/** Decodes a fare-side Remote Config payload after checking its `schema` major (contracts §2). */
internal fun <T> decodeFareSidePayload(raw: String, serializer: KSerializer<T>): T {
    val obj = ConfigJson.parseToJsonElement(raw) as? JsonObject
        ?: throw IllegalArgumentException("payload must be a JSON object")
    require(schemaMajor(obj) == SupportedSchemas.MAJOR) { "unsupported schema ${obj["schema"]}" }
    return ConfigJson.decodeFromJsonElement(serializer, obj)
}

private val SCREEN_BY_WIRE: Map<String, ScreenType> =
    ScreenType.entries.associateBy { ScreenType.serializer().descriptor.getElementName(it.ordinal) }

private fun compileFareRegex(pattern: String, where: String): Pattern = try {
    Pattern.compile(pattern)
} catch (e: PatternSyntaxException) {
    throw IllegalArgumentException("$where doesn't compile under RE2/J", e)
}
