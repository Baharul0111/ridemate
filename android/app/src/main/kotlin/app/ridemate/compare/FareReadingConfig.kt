package app.ridemate.compare

import app.ridemate.config.ConfigKeys
import app.ridemate.config.ConfigRepository
import app.ridemate.core.config.FareParsersConfig
import app.ridemate.core.config.SanityBandsConfig
import app.ridemate.core.config.parseFareParsers
import app.ridemate.core.config.parseSanityBands
import app.ridemate.core.mask.MaskConfig
import app.ridemate.core.model.Provider

/** The Remote Config the fare reader uses (contracts §2.2, §2.3, §2.7). */
interface FareReadingConfig {
    /** `fare_parsers` (remote → last good → bundled); null when no layer is valid. */
    fun fareParsers(): FareParsersConfig?

    /** `sanity_bands`, falling back to the contracts defaults. */
    fun sanityBands(): SanityBandsConfig

    fun maskConfig(): MaskConfig = MaskConfig.DEFAULT

    /**
     * Kill switches: live apps need `ai_reader_enabled` and `reader_enabled_<app>`; demo providers follow
     * `demo_enabled` only (M9: the demo works when AI is capped or off, from the server's demo cache).
     */
    fun aiReaderEnabled(provider: Provider): Boolean
}

/** [FareReadingConfig] over the platform's [ConfigRepository]; payloads are re-resolved on every call. */
class RemoteFareReadingConfig(private val repository: ConfigRepository) : FareReadingConfig {
    override fun fareParsers(): FareParsersConfig? = repository.resolve(ConfigKeys.FARE_PARSERS, ::parseFareParsers)?.value

    override fun sanityBands(): SanityBandsConfig =
        repository.resolve(ConfigKeys.SANITY_BANDS, ::parseSanityBands)?.value ?: SanityBandsConfig.DEFAULT

    override fun aiReaderEnabled(provider: Provider): Boolean {
        val switches = repository.killSwitches.value
        return when {
            provider.isNetwork -> false
            provider.isDemo -> switches.demoEnabled
            else -> switches.aiReaderEnabled && switches.readerEnabledFor(provider)
        }
    }
}
