package app.ridemate.config

import app.ridemate.core.model.Provider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Remote Config keys (architecture §5, contracts §2). */
object ConfigKeys {
    const val LAUNCH_TEMPLATES = "launch_templates"
    const val NOTIFICATION_TEMPLATES = "notification_templates"
    const val FARE_PARSERS = "fare_parsers"
    const val CANCEL_CARDS = "cancel_cards"
    const val FEE_RULES = "fee_rules"
    const val SANITY_BANDS = "sanity_bands"
    const val AI_READER_ENABLED = "ai_reader_enabled"
    const val ONDC_ENABLED = "ondc_enabled"
    const val DEMO_ENABLED = "demo_enabled"
    const val MODEL_ROUTE = "model_route"
    const val LATEST_VERSION = "latest_version"
    const val MIN_SUPPORTED_VERSION = "min_supported_version"
    const val CAP_PROFILE = "cap_profile"
    const val CREDIT_EXPIRY_DATE = "credit_expiry_date"
    const val BUDGET_HORIZON_DAYS = "budget_horizon_days"
    const val BUDGET_HORIZON_START = "budget_horizon_start"
    const val API_BASE_URL = "api_base_url"
    const val JUDGE_ALLOWLIST = "judge_allowlist"
    const val OWNER_EMAILS_SHA256 = "owner_emails_sha256"

    /** `reader_enabled_uber` / `_ola` / `_rapido`. */
    fun readerEnabled(provider: Provider): String = "reader_enabled_${provider.wireId}"

    /** Keys watched by the real-time listener and exposed as StateFlows. */
    val KILL_SWITCHES: Set<String> =
        setOf(AI_READER_ENABLED, ONDC_ENABLED, DEMO_ENABLED) + Provider.LIVE_APPS.map { readerEnabled(it) }
}

/**
 * Scalar defaults of contracts §2.7, used when `assets/config/<key>.json` does not provide the key. JSON-valued
 * payloads (templates, parsers, …) have no built-in default: they come only from the bundled assets.
 */
object BuiltInDefaults {
    val VALUES: Map<String, String> = buildMap {
        put(ConfigKeys.AI_READER_ENABLED, "true")
        Provider.LIVE_APPS.forEach { put(ConfigKeys.readerEnabled(it), "true") }
        put(ConfigKeys.ONDC_ENABLED, "true")
        put(ConfigKeys.DEMO_ENABLED, "true")
        put(ConfigKeys.MODEL_ROUTE, """{"schema":"1.0","read":"modal_spark_open","parse":"modal_spark_open"}""")
        put(
            ConfigKeys.LATEST_VERSION,
            """{"schema":"1.0","version_code":1,"version_name":"1.0.0-hackathon",""" +
                """"url":"https://github.com/Baharul0111/ridemate/releases/latest","sha256":""}""",
        )
        put(ConfigKeys.MIN_SUPPORTED_VERSION, "1")
        put(ConfigKeys.CAP_PROFILE, "normal")
        put(ConfigKeys.CREDIT_EXPIRY_DATE, "unknown")
        put(ConfigKeys.BUDGET_HORIZON_DAYS, "90")
        put(ConfigKeys.BUDGET_HORIZON_START, "2026-10-28")
        put(ConfigKeys.API_BASE_URL, "")
        put(ConfigKeys.JUDGE_ALLOWLIST, """{"schema":"1.0","sha256_emails":[]}""")
        put(ConfigKeys.OWNER_EMAILS_SHA256, "[]")
    }
}

/** `latest_version` (contracts §2.7). */
@Serializable
data class LatestVersion(
    val schema: String,
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    val url: String = "",
    val sha256: String = "",
)

/** The remote kill switches (§4.2 Config), refreshed in real time. */
data class KillSwitches(
    val aiReaderEnabled: Boolean = true,
    val readerEnabled: Map<Provider, Boolean> = Provider.LIVE_APPS.associateWith { true },
    val ondcEnabled: Boolean = true,
    val demoEnabled: Boolean = true,
) {
    /** Whether fare reading may run for [provider] (demo providers follow `demo_enabled`, ONDC `ondc_enabled`). */
    fun readerEnabledFor(provider: Provider): Boolean = when {
        provider.isDemo -> demoEnabled
        provider.isNetwork -> ondcEnabled
        else -> readerEnabled[provider] ?: true
    }
}
