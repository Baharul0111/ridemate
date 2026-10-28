package app.ridemate.update

import app.ridemate.config.LatestVersion
import app.ridemate.util.BuildInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * What the update banner shows (contracts §2.7, goal 28).
 *
 * @property available a newer signed APK is published ([versionName], [url], [sha256] describe it).
 * @property belowMinSupported this build is older than `min_supported_version`.
 * @property forceUpdate surface the blocking "please update" screen now: below the minimum **and** no session is
 *   active (F5: a forced update never interrupts an active session).
 */
data class UpdateState(
    val available: Boolean = false,
    val versionCode: Int? = null,
    val versionName: String? = null,
    val url: String? = null,
    val sha256: String? = null,
    val belowMinSupported: Boolean = false,
    val forceUpdate: Boolean = false,
)

/**
 * Compares the installed versionCode with Remote Config `latest_version` / `min_supported_version`. Recomputed on
 * every config activation ([configEpoch]) and whenever a session starts or ends ([sessionActive]).
 */
class UpdateChecker(
    private val build: BuildInfo,
    private val latestVersion: () -> LatestVersion?,
    private val minSupportedVersion: () -> Int,
    configEpoch: StateFlow<Long>,
    private val sessionActive: StateFlow<Boolean>,
    scope: CoroutineScope,
) {
    val state: StateFlow<UpdateState> = combine(configEpoch, sessionActive) { _, active -> compute(active) }
        .stateIn(scope, SharingStarted.Eagerly, compute(sessionActive.value))

    /** Computes the state now (no caching). */
    fun check(): UpdateState = compute(sessionActive.value)

    private fun compute(sessionIsActive: Boolean): UpdateState {
        val latest = runCatching { latestVersion() }.getOrNull()
        val url = latest?.url?.takeIf { isHttpsUrl(it) }
        val sha = latest?.sha256?.lowercase()?.takeIf { SHA256.matches(it) }
        val available = latest != null && url != null && latest.versionCode > build.versionCode
        val below = build.versionCode < runCatching { minSupportedVersion() }.getOrDefault(1)
        return UpdateState(
            available = available,
            versionCode = latest?.versionCode?.takeIf { available },
            versionName = latest?.versionName?.takeIf { available },
            url = url.takeIf { available },
            sha256 = sha.takeIf { available },
            belowMinSupported = below,
            forceUpdate = below && !sessionIsActive,
        )
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")

        fun isHttpsUrl(url: String): Boolean {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        }
    }
}
