package app.ridemate.settings

import app.ridemate.ui.KeyValueStore
import app.ridemate.ui.getBoolean
import app.ridemate.ui.putBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences owned by Settings. Other areas read them:
 * - [keepSearchingDefault]: the "keep searching" default for new comparisons (goal 16; **off** by default; turning
 *   it on needs the fair-play warning to be accepted).
 * - [languageTag]: the chosen language on Android 9–12 (Android 13+ keeps it in the system's per-app locale).
 */
class SettingsStore(private val kv: KeyValueStore) {
    private val _keepSearching = MutableStateFlow(kv.getBoolean(K_KEEP_SEARCHING, default = false))
    val keepSearchingDefault: StateFlow<Boolean> = _keepSearching.asStateFlow()

    fun setKeepSearchingDefault(on: Boolean) {
        kv.putBoolean(K_KEEP_SEARCHING, on)
        _keepSearching.value = on
    }

    var languageTag: String?
        get() = kv.get(K_LANGUAGE)
        set(value) = kv.put(K_LANGUAGE, value)

    companion object {
        const val PREFS_NAME = "ridemate_settings"
        const val K_KEEP_SEARCHING = "keep_searching_default"
        const val K_LANGUAGE = "language_tag"
    }
}
