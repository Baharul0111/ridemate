package app.ridemate.core.config

import app.ridemate.core.model.Provider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * Remote Config scalar key names (contracts §2.7). There are no identity lists among them: judges and owners stay on
 * the server (every install can fetch the client template), and the phone learns "owner" from `/v1/config-check`.
 */
object ScalarKeys {
    const val AI_READER_ENABLED = "ai_reader_enabled"
    const val READER_ENABLED_UBER = "reader_enabled_uber"
    const val READER_ENABLED_OLA = "reader_enabled_ola"
    const val READER_ENABLED_RAPIDO = "reader_enabled_rapido"
    const val ONDC_ENABLED = "ondc_enabled"
    const val DEMO_ENABLED = "demo_enabled"
    const val BETTER_RIDE_ENABLED = "better_ride_enabled"
    const val MODEL_ROUTE = "model_route"
    const val LATEST_VERSION = "latest_version"
    const val MIN_SUPPORTED_VERSION = "min_supported_version"
    const val CAP_PROFILE = "cap_profile"
    const val CREDIT_EXPIRY_DATE = "credit_expiry_date"
    const val BUDGET_HORIZON_DAYS = "budget_horizon_days"
    const val BUDGET_HORIZON_START = "budget_horizon_start"
    const val API_BASE_URL = "api_base_url"
}

enum class CapProfile { NORMAL, JUDGING }

@Serializable
data class ModelRoute(val schema: String, val read: String, val parse: String)

@Serializable
data class LatestVersion(
    val schema: String,
    @SerialName("version_code") val versionCode: Long,
    @SerialName("version_name") val versionName: String,
    val url: String,
    val sha256: String = "",
)

/**
 * Typed view of the scalar keys. Every value falls back to its contracts §2.7 default on its own when missing or
 * malformed, so one bad key never takes the others down; JSON values are schema-major checked.
 */
data class ScalarConfig(
    val aiReaderEnabled: Boolean = true,
    val readerEnabledUber: Boolean = true,
    val readerEnabledOla: Boolean = true,
    val readerEnabledRapido: Boolean = true,
    val ondcEnabled: Boolean = true,
    val demoEnabled: Boolean = true,
    /** Opt-in better-ride offers ("Keep searching") may run; false is the remote kill switch. */
    val betterRideEnabled: Boolean = true,
    val modelRoute: ModelRoute? = null,
    val latestVersion: LatestVersion? = null,
    val minSupportedVersion: Long = 1,
    val capProfile: CapProfile = CapProfile.NORMAL,
    /** Null means `unknown` (the budget horizon then runs [budgetHorizonDays] from [budgetHorizonStart]). */
    val creditExpiryDate: LocalDate? = null,
    val budgetHorizonDays: Int = 90,
    val budgetHorizonStart: LocalDate = DEFAULT_HORIZON_START,
    /** Empty until set at deploy; AI calls are impossible without it. */
    val apiBaseUrl: String = "",
) {
    /** Kill switches (§4.2 Config): AI reading for a ride app needs both the global and the per-app switch. */
    fun aiReaderEnabledFor(app: Provider): Boolean = aiReaderEnabled && when (app) {
        Provider.UBER -> readerEnabledUber
        Provider.OLA -> readerEnabledOla
        Provider.RAPIDO -> readerEnabledRapido
        else -> true
    }

    companion object {
        val DEFAULT_HORIZON_START: LocalDate = LocalDate.of(2026, 10, 28)
        private val TRUE = setOf("true", "1", "yes", "on")
        private val FALSE = setOf("false", "0", "no", "off")

        /** Builds the view from Remote Config's string values (keys absent from [values] keep their defaults). */
        fun from(values: Map<String, String>): ScalarConfig {
            val d = ScalarConfig()
            fun bool(key: String, default: Boolean): Boolean {
                val v = values[key]?.trim()?.lowercase() ?: return default
                return when (v) {
                    in TRUE -> true
                    in FALSE -> false
                    else -> default
                }
            }
            fun <T> json(key: String, parse: (String) -> T): T? =
                values[key]?.takeIf { it.isNotBlank() }?.let { runCatching { parse(it) }.getOrNull() }
            return ScalarConfig(
                aiReaderEnabled = bool(ScalarKeys.AI_READER_ENABLED, d.aiReaderEnabled),
                readerEnabledUber = bool(ScalarKeys.READER_ENABLED_UBER, d.readerEnabledUber),
                readerEnabledOla = bool(ScalarKeys.READER_ENABLED_OLA, d.readerEnabledOla),
                readerEnabledRapido = bool(ScalarKeys.READER_ENABLED_RAPIDO, d.readerEnabledRapido),
                ondcEnabled = bool(ScalarKeys.ONDC_ENABLED, d.ondcEnabled),
                demoEnabled = bool(ScalarKeys.DEMO_ENABLED, d.demoEnabled),
                betterRideEnabled = bool(ScalarKeys.BETTER_RIDE_ENABLED, d.betterRideEnabled),
                modelRoute = json(ScalarKeys.MODEL_ROUTE) { decodeFareSidePayload(it, ModelRoute.serializer()) },
                latestVersion = json(ScalarKeys.LATEST_VERSION) {
                    decodeFareSidePayload(it, LatestVersion.serializer()).also { v ->
                        require(v.versionCode > 0 && v.url.startsWith("https://")) { "invalid latest_version" }
                    }
                },
                minSupportedVersion = values[ScalarKeys.MIN_SUPPORTED_VERSION]?.trim()?.toLongOrNull()
                    ?.takeIf { it >= 1 } ?: d.minSupportedVersion,
                capProfile = when (values[ScalarKeys.CAP_PROFILE]?.trim()?.lowercase()) {
                    "judging" -> CapProfile.JUDGING
                    else -> CapProfile.NORMAL
                },
                creditExpiryDate = values[ScalarKeys.CREDIT_EXPIRY_DATE]?.let(::dateOrNull),
                budgetHorizonDays = values[ScalarKeys.BUDGET_HORIZON_DAYS]?.trim()?.toIntOrNull()
                    ?.takeIf { it in 1..3650 } ?: d.budgetHorizonDays,
                budgetHorizonStart = values[ScalarKeys.BUDGET_HORIZON_START]?.let(::dateOrNull) ?: d.budgetHorizonStart,
                apiBaseUrl = values[ScalarKeys.API_BASE_URL]?.trim()?.takeIf { it.startsWith("https://") } ?: d.apiBaseUrl,
            )
        }

        private fun dateOrNull(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim()) }.getOrNull()
    }
}
