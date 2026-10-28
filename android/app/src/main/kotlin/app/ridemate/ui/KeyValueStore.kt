package app.ridemate.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny string key-value store for the shell's small persistent answers (onboarding progress, settings, developer
 * mode). Production uses [SharedPrefsKeyValueStore]; tests use [InMemoryKeyValueStore].
 */
interface KeyValueStore {
    fun get(key: String): String?

    /** Stores [value]; null removes the key. */
    fun put(key: String, value: String?)

    fun clear()
}

fun KeyValueStore.getBoolean(key: String, default: Boolean = false): Boolean = when (get(key)) {
    "true" -> true
    "false" -> false
    else -> default
}

fun KeyValueStore.putBoolean(key: String, value: Boolean) = put(key, value.toString())

/** A set of enum names stored comma-separated; unknown names (from an older or newer build) are ignored. */
inline fun <reified E : Enum<E>> KeyValueStore.getEnumSet(key: String): Set<E> =
    get(key).orEmpty().split(',').mapNotNull { name -> enumValues<E>().firstOrNull { it.name == name } }.toSet()

fun <E : Enum<E>> KeyValueStore.putEnumSet(key: String, values: Set<E>) =
    put(key, values.map { it.name }.sorted().joinToString(",").ifEmpty { null })

inline fun <reified E : Enum<E>> KeyValueStore.getEnum(key: String): E? =
    get(key)?.let { name -> enumValues<E>().firstOrNull { it.name == name } }

class SharedPrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    constructor(context: Context, name: String) : this(context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE))

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) = prefs.edit { if (value == null) remove(key) else putString(key, value) }

    override fun clear() = prefs.edit { clear() }
}

class InMemoryKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    private val values = ConcurrentHashMap(initial)

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }

    override fun clear() = values.clear()

    fun snapshot(): Map<String, String> = values.toMap()
}
