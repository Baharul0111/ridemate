package app.ridemate.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import java.util.Locale

/** The languages RideMate ships (goal 26). [SYSTEM] follows the phone. */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    HINDI("hi"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage {
            val language = tag?.substringBefore('-')?.lowercase(Locale.ROOT)
            return entries.firstOrNull { it.tag != null && it.tag == language } ?: SYSTEM
        }
    }
}

/**
 * The in-app language switch. On Android 13+ it uses the system per-app language ([LocaleManager]), which also
 * shows in Android's own "App languages" settings and recreates the activity by itself. On Android 10–12 it stores
 * the choice and the activity applies it in `attachBaseContext` ([LegacyLocale]) after a recreate.
 */
interface LanguageController {
    fun current(): AppLanguage

    /** Applies [language]; returns true when the caller must recreate the activity (Android 10–12). */
    fun set(language: AppLanguage): Boolean
}

/** Where the per-app locale is actually stored, so tests can swap it. */
interface AppLocaleBackend {
    val recreatesActivity: Boolean
    fun get(): String?
    fun set(tag: String?)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class SystemLocaleBackend(context: Context) : AppLocaleBackend {
    private val manager = context.applicationContext.getSystemService(LocaleManager::class.java)
    override val recreatesActivity: Boolean = true

    override fun get(): String? = manager?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()

    override fun set(tag: String?) {
        manager?.applicationLocales = if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }
}

class StoredLocaleBackend(private val store: SettingsStore) : AppLocaleBackend {
    override val recreatesActivity: Boolean = false
    override fun get(): String? = store.languageTag
    override fun set(tag: String?) {
        store.languageTag = tag
    }
}

class DefaultLanguageController(private val backend: AppLocaleBackend, private val store: SettingsStore) : LanguageController {
    /** What is in effect: on Android 13+ the system's per-app locale (the user may change it in Android settings). */
    override fun current(): AppLanguage = AppLanguage.fromTag(backend.get())

    override fun set(language: AppLanguage): Boolean {
        if (language == current()) return false
        backend.set(language.tag)
        // Kept on every version, so the choice survives an upgrade path and reports can name it.
        store.languageTag = language.tag
        return !backend.recreatesActivity
    }

    companion object {
        fun create(context: Context, store: SettingsStore): LanguageController = DefaultLanguageController(
            backend = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) SystemLocaleBackend(context) else StoredLocaleBackend(store),
            store = store,
        )
    }
}

/** Android 10–12: applies the stored language to an activity's base context. */
object LegacyLocale {
    /**
     * [base] in the stored language. With nothing stored ("Phone's language") the process default locale follows the
     * phone again, in case an earlier choice in this process changed it ([systemLocale]: the phone's own locale).
     */
    fun wrap(
        base: Context,
        sdkInt: Int = Build.VERSION.SDK_INT,
        systemLocale: () -> Locale = { Resources.getSystem().configuration.locales[0] },
    ): Context {
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SettingsStore.K_LANGUAGE, null)
        if (tag == null) {
            runCatching { systemLocale() }.getOrNull()?.let { if (it != Locale.getDefault()) Locale.setDefault(it) }
            return base
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
