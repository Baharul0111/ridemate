package app.ridemate.config

import android.content.SharedPreferences
import androidx.core.content.edit
import app.ridemate.core.config.ConfigJson
import app.ridemate.core.config.Layered
import app.ridemate.core.config.resolveLayered
import app.ridemate.core.model.Provider
import app.ridemate.crash.CrashReporter
import app.ridemate.crash.CrashSite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest

/** The three layers of one key, for core's `resolveLayered(remote, lastGood, bundled, parse)`. */
data class RawConfig(
    /** Active server value, or null when the server has none. */
    val remote: String?,
    /** The last remote payload a validator accepted (SharedPreferences), or null. */
    val lastGood: String?,
    /** The APK default (`assets/config/<key>.json`, else the §2.7 built-in scalar), or null. */
    val bundled: String?,
)

/** For the "My data" / developer screens. [remoteFingerprint] identifies the active server values (null: none). */
data class ConfigInfo(
    val lastFetchUtcMs: Long?,
    val status: FetchStatus,
    val remoteFingerprint: String?,
)

/**
 * Remote Config for the phone (architecture §4.2 Config, contracts §2).
 *
 * - Nothing waits for the network: [start] and [refreshInBackground] launch `fetchAndActivate` (5 s fetch
 *   timeout) and return at once; every read is answered from what is active right now, the last-good cache or the
 *   bundled default. Never await a fetch before a session.
 * - Kill switches (`ai_reader_enabled`, `reader_enabled_<app>`, `ondc_enabled`, `demo_enabled`,
 *   `better_ride_enabled`) are StateFlows updated by the real-time listener within seconds of a console change.
 * - JSON payloads go through [resolve] (or [raw] + core `resolveLayered`): a remote payload is used only if it
 *   parses with a supported schema major; the last-good cache is written only when a validator accepted it.
 * - [epoch] increases after every activation so consumers re-resolve their payloads.
 */
class ConfigRepository(
    private val backend: RemoteConfigBackend,
    bundledSource: BundledConfigSource,
    private val lastGoodPrefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val crash: CrashReporter? = null,
    private val minimumFetchIntervalSeconds: Long = DEFAULT_MIN_FETCH_INTERVAL_S,
) {
    private val bundled: Map<String, String> by lazy {
        BuiltInDefaults.VALUES + runCatching { bundledSource.load() }.getOrDefault(emptyMap())
    }

    private val _epoch = MutableStateFlow(0L)
    val epoch: StateFlow<Long> = _epoch.asStateFlow()

    private val _killSwitches = MutableStateFlow(KillSwitches())
    val killSwitches: StateFlow<KillSwitches> = _killSwitches.asStateFlow()

    private val _aiReaderEnabled = MutableStateFlow(true)
    val aiReaderEnabled: StateFlow<Boolean> = _aiReaderEnabled.asStateFlow()

    private val _ondcEnabled = MutableStateFlow(true)
    val ondcEnabled: StateFlow<Boolean> = _ondcEnabled.asStateFlow()

    private val _demoEnabled = MutableStateFlow(true)
    val demoEnabled: StateFlow<Boolean> = _demoEnabled.asStateFlow()

    private val _betterRideEnabled = MutableStateFlow(true)

    /** `better_ride_enabled`: the opt-in better-ride offers ("Keep searching") may run. */
    val betterRideEnabled: StateFlow<Boolean> = _betterRideEnabled.asStateFlow()

    private val readerFlows: Map<Provider, MutableStateFlow<Boolean>> = Provider.LIVE_APPS.associateWith { MutableStateFlow(true) }

    private var startJob: Job? = null
    private var updateRegistration: AutoCloseable? = null

    @Volatile
    private var refreshJob: Job? = null

    init {
        publish()
    }

    /** `reader_enabled_<app>` as a StateFlow (demo providers follow `demo_enabled`, ONDC `ondc_enabled`). */
    fun readerEnabled(provider: Provider): StateFlow<Boolean> = when {
        provider.isDemo -> demoEnabled
        provider.isNetwork -> ondcEnabled
        else -> readerFlows.getValue(provider).asStateFlow()
    }

    /**
     * Registers the real-time listener, then (in the background) applies the 5 s fetch timeout and runs the first
     * fetch. Returns at once; idempotent.
     */
    @Synchronized
    fun start(): Job {
        startJob?.let { return it }
        updateRegistration = runCatching {
            backend.addUpdateListener { _ -> scope.launch { activateQuietly() } }
        }.onFailure { crash?.recordNonFatal(it, CrashSite.CONFIG) }.getOrNull()
        val job = scope.launch {
            try {
                backend.configure(FETCH_TIMEOUT_S, minimumFetchIntervalSeconds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                crash?.recordNonFatal(e, CrashSite.CONFIG)
            }
            refreshInBackground().join()
        }
        startJob = job
        return job
    }

    /** Fire-and-forget `fetchAndActivate` (app start, every return to the foreground). Coalesces overlapping calls. */
    @Synchronized
    fun refreshInBackground(): Job {
        refreshJob?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch {
            try {
                withTimeout((FETCH_TIMEOUT_S + 1) * 1000) { backend.fetchAndActivate() }
            } catch (e: CancellationException) {
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                crash?.breadcrumb(CrashSite.CONFIG)
            } catch (e: Exception) {
                crash?.breadcrumb(CrashSite.CONFIG)
            }
            publish()
        }
        refreshJob = job
        return job
    }

    private suspend fun activateQuietly() {
        try {
            backend.activate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            crash?.breadcrumb(CrashSite.CONFIG)
        }
        publish()
    }

    /** Recomputes the kill switches from the active values and bumps [epoch]. */
    private fun publish() {
        val switches = KillSwitches(
            aiReaderEnabled = boolean(ConfigKeys.AI_READER_ENABLED, true),
            readerEnabled = Provider.LIVE_APPS.associateWith { boolean(ConfigKeys.readerEnabled(it), true) },
            ondcEnabled = boolean(ConfigKeys.ONDC_ENABLED, true),
            demoEnabled = boolean(ConfigKeys.DEMO_ENABLED, true),
            betterRideEnabled = boolean(ConfigKeys.BETTER_RIDE_ENABLED, true),
        )
        _killSwitches.value = switches
        _aiReaderEnabled.value = switches.aiReaderEnabled
        _ondcEnabled.value = switches.ondcEnabled
        _demoEnabled.value = switches.demoEnabled
        _betterRideEnabled.value = switches.betterRideEnabled
        readerFlows.forEach { (provider, flow) -> flow.value = switches.readerEnabledFor(provider) }
        _epoch.update { it + 1 }
    }

    // -----------------------------------------------------------------------------------------------------------
    // Layered JSON payloads
    // -----------------------------------------------------------------------------------------------------------

    fun raw(key: String): RawConfig = RawConfig(
        remote = remoteOrNull(key),
        lastGood = lastGoodPrefs.getString(key, null),
        bundled = bundled[key],
    )

    /**
     * Stores [payload] as the last-good value of [key] only if [validator] accepts it (a throwing validator
     * rejects). Returns whether it was stored.
     */
    fun acceptLastGood(key: String, payload: String, validator: (String) -> Boolean): Boolean {
        val ok = try {
            validator(payload)
        } catch (e: Exception) {
            false
        }
        if (ok) lastGoodPrefs.edit { putString(key, payload) }
        return ok
    }

    /**
     * Resolves [key] through core's `resolveLayered`: remote if it parses with a supported schema, else last-good,
     * else bundled. A winning remote payload becomes the new last-good (parsing is the validator). Null only when
     * no layer parses (e.g. no bundled default and nothing valid remote).
     */
    fun <T> resolve(key: String, parse: (String) -> T): Layered<T>? {
        val layers = raw(key)
        val layered = try {
            resolveLayered(layers.remote, layers.lastGood, layers.bundled.orEmpty(), parse)
        } catch (e: Exception) {
            return null
        }
        if (layered.source == app.ridemate.core.config.Layer.REMOTE && layered.raw != layers.lastGood) {
            lastGoodPrefs.edit { putString(key, layered.raw) }
        }
        return layered
    }

    // -----------------------------------------------------------------------------------------------------------
    // Scalars (contracts §2.7)
    // -----------------------------------------------------------------------------------------------------------

    /** Active remote value, else the bundled default, else "". */
    fun string(key: String): String = remoteOrNull(key) ?: bundled[key].orEmpty()

    fun boolean(key: String, default: Boolean): Boolean =
        parseBoolean(remoteOrNull(key)) ?: parseBoolean(bundled[key]) ?: default

    fun long(key: String, default: Long): Long =
        remoteOrNull(key)?.trim()?.toLongOrNull() ?: bundled[key]?.trim()?.toLongOrNull() ?: default

    /** `api_base_url`; "" when not configured (the API client then returns NotConfigured). */
    fun apiBaseUrl(): String = string(ConfigKeys.API_BASE_URL).trim()

    fun minSupportedVersion(): Int = long(ConfigKeys.MIN_SUPPORTED_VERSION, 1).toInt()

    /** `latest_version`, validated (schema major, decodable); null if no layer is valid. */
    fun latestVersion(): LatestVersion? =
        resolve(ConfigKeys.LATEST_VERSION) { ConfigJson.decodeFromString(LatestVersion.serializer(), it) }?.value

    fun info(): ConfigInfo {
        val backendInfo = runCatching { backend.info() }.getOrDefault(BackendInfo(null, FetchStatus.NEVER))
        val remote = runCatching { backend.remoteKeys() }.getOrDefault(emptySet()).sorted()
            .mapNotNull { key -> remoteOrNull(key)?.let { "$key=$it" } }
        val fingerprint = if (remote.isEmpty()) null else sha256Hex(remote.joinToString("\n")).take(12)
        return ConfigInfo(backendInfo.lastFetchUtcMs, backendInfo.status, fingerprint)
    }

    private fun remoteOrNull(key: String): String? = try {
        backend.remoteValue(key)
    } catch (e: Exception) {
        null
    }

    companion object {
        const val FETCH_TIMEOUT_S: Long = 5
        const val DEFAULT_MIN_FETCH_INTERVAL_S: Long = 3600
        const val PREFS_NAME = "ridemate_config_last_good"

        fun parseBoolean(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }

        private fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
