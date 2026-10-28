package app.ridemate.config

import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.tasks.await

/** [RemoteConfigBackend] over Firebase Remote Config; [remoteConfig] is resolved lazily. */
class FirebaseRemoteConfigBackend(private val remoteConfig: () -> FirebaseRemoteConfig) : RemoteConfigBackend {

    override suspend fun configure(fetchTimeoutSeconds: Long, minimumFetchIntervalSeconds: Long) {
        val settings = FirebaseRemoteConfigSettings.Builder()
            .setFetchTimeoutInSeconds(fetchTimeoutSeconds)
            .setMinimumFetchIntervalInSeconds(minimumFetchIntervalSeconds)
            .build()
        remoteConfig().setConfigSettingsAsync(settings).await()
    }

    override suspend fun fetchAndActivate(): Boolean = remoteConfig().fetchAndActivate().await()

    override suspend fun activate(): Boolean = remoteConfig().activate().await()

    override fun remoteValue(key: String): String? {
        val value = remoteConfig().getValue(key)
        return if (value.source == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE) value.asString() else null
    }

    override fun remoteKeys(): Set<String> =
        remoteConfig().all.filterValues { it.source == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE }.keys

    override fun addUpdateListener(onUpdate: (Set<String>) -> Unit): AutoCloseable {
        val registration = remoteConfig().addOnConfigUpdateListener(object : ConfigUpdateListener {
            override fun onUpdate(configUpdate: ConfigUpdate) = onUpdate(configUpdate.updatedKeys)

            override fun onError(error: FirebaseRemoteConfigException) {
                // The SDK retries the real-time stream itself; foreground fetches still apply updates.
            }
        })
        return AutoCloseable { registration.remove() }
    }

    override fun info(): BackendInfo {
        val info = remoteConfig().info
        val status = when (info.lastFetchStatus) {
            FirebaseRemoteConfig.LAST_FETCH_STATUS_SUCCESS -> FetchStatus.SUCCESS
            FirebaseRemoteConfig.LAST_FETCH_STATUS_FAILURE -> FetchStatus.FAILURE
            FirebaseRemoteConfig.LAST_FETCH_STATUS_THROTTLED -> FetchStatus.THROTTLED
            else -> FetchStatus.NEVER
        }
        return BackendInfo(info.fetchTimeMillis.takeIf { it > 0 }, status)
    }
}
