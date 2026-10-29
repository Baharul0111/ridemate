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

/**
 * Android 10–12: the choice is stored, and [onChanged] applies it to the running process at once (the application's
 * resources, which notifications, toasts and the bubble use; the notification channel names). Activities and the
 * session service pick it up in `attachBaseContext` ([LegacyLocale.wrap]).
 */
class StoredLocaleBackend(private val store: SettingsStore, private val onChanged: (String?) -> Unit = {}) : AppLocaleBackend {
    override val recreatesActivity: Boolean = false
    override fun get(): String? = store.languageTag
    override fun set(tag: String?) {
        store.languageTag = tag
        onChanged(tag)
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
            backend = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SystemLocaleBackend(context)
            } else {
                StoredLocaleBackend(store) { tag ->
                    val application = context.applicationContext
                    LegacyLocale.applyToProcess(application, tag)
                    // Channel names are shown in Android's settings: rename them in the new language.
                    runCatching { app.ridemate.notify.GuardianChannels.ensure(application) }
                }
            },
            store = store,
        )
    }
}

/**
 * The base of RideMate's own activities other than [app.ridemate.MainActivity] (the comparison and price form opened
 * from the bubble, capture consent, opening a ride app, a shared screenshot): on Android 10–12 they show the in-app
 * language too, not only the phone's.
 */
open class LocalizedActivity : androidx.activity.ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LegacyLocale.wrap(newBase))
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

    /**
     * A language change while the process runs (Android 10–12): the application's resources switch now, so the
     * bubble, notifications and toasts (which use the application context) follow without a restart.
     */
    fun applyToProcess(
        app: Context,
        tag: String?,
        sdkInt: Int = Build.VERSION.SDK_INT,
        systemLocale: () -> Locale = { Resources.getSystem().configuration.locales[0] },
    ) {
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) return
        val locale = tag?.let(Locale::forLanguageTag) ?: runCatching { systemLocale() }.getOrNull() ?: return
        Locale.setDefault(locale)
        val resources = app.resources
        val config = Configuration(resources.configuration)
        config.setLocales(LocaleList(locale))
        @Suppress("DEPRECATION") // The only way to switch an existing Resources object; Android 13+ never gets here.
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}
