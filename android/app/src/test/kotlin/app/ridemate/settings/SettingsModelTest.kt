package app.ridemate.settings

import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.R
import app.ridemate.RideMateApp
import app.ridemate.capture.CaptureConsentActivity
import app.ridemate.capture.ShareReceiverActivity
import app.ridemate.compare.ComparisonActivity
import app.ridemate.launch.OpenRideAppActivity
import app.ridemate.session.RideSessionService
import app.ridemate.auth.AuthState
import app.ridemate.config.KillSwitches
import app.ridemate.onboarding.AppPermission
import app.ridemate.privacy.AccountErase
import app.ridemate.privacy.EraseStatus
import app.ridemate.privacy.ServerErase
import app.ridemate.privacy.SignInKind
import app.ridemate.testing.FakeLocaleBackend
import app.ridemate.testing.TestData
import app.ridemate.testing.TestRideMateApp
import app.ridemate.ui.InMemoryKeyValueStore
import app.ridemate.update.UpdateState
import app.ridemate.util.BuildInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as TestRideMateApp
    private val graph = app.graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun model(language: LanguageController = DefaultLanguageController(FakeLocaleBackend(), graph.appShell.settingsStore)) =
        SettingsModel(
            store = graph.appShell.settingsStore,
            language = language,
            killSwitches = graph.configRepository.killSwitches,
            eraseStatus = graph.eraseUseCase.status,
            erase = { graph.eraseUseCase.erase() },
            auth = graph.authGateway,
            appScope = graph.appScope,
            scope = scope,
        )

    private suspend fun awaitDialog(model: SettingsModel, predicate: (EraseDialog) -> Boolean): EraseDialog = withTimeout(5_000) {
        while (!predicate(model.state.value.eraseDialog)) delay(10)
        model.state.value.eraseDialog
    }

    @Test
    fun `erase flow wipes the phone, deletes the account and restarts onboarding`() = runBlocking {
        val shell = graph.appShell
        shell.onboardingStore.confirmAdult()
        shell.onboardingStore.markCompleted()
        graph.db.sessionDao().insert(TestData.session())
        app.fakes.auth.signInWithGoogleIdToken("token")
        assertTrue(graph.authGateway.state.value is AuthState.Google)

        val settings = model()
        settings.requestErase()
        assertEquals(EraseDialog.Confirm, settings.state.value.eraseDialog)
        settings.confirmErase().join()
        val result = awaitDialog(settings) { it is EraseDialog.Result } as EraseDialog.Result
        val finished = result.status as EraseStatus.Finished
        // No server configured in tests: nothing to erase there; the Firebase account is deleted on the phone.
        assertEquals(ServerErase.SKIPPED, finished.result.server)
        assertEquals(AccountErase.DELETED, finished.result.account)
        assertEquals(AuthState.SignedOut, graph.authGateway.state.value)
        assertEquals(0, graph.db.sessionDao().count())
        // The shell's own wiper ran: onboarding starts again from the 18+ check.
        assertFalse(shell.onboardingStore.answers.value.completed)
        assertFalse(shell.onboardingStore.answers.value.adultConfirmed)
        settings.dismissEraseDialog()
        assertEquals(EraseDialog.Hidden, settings.state.value.eraseDialog)
    }

    @Test
    fun `keep searching is off by default and needs the warning to turn on`() {
        val settings = model()
        assertFalse(settings.state.value.keepSearching)
        settings.setKeepSearching(true)
        assertTrue(settings.state.value.keepSearchingWarning)
        assertFalse("not on before the warning is accepted", graph.appShell.settingsStore.keepSearchingDefault.value)
        settings.dismissKeepSearchingWarning()
        assertFalse(settings.state.value.keepSearching)
        settings.setKeepSearching(true)
        settings.confirmKeepSearching()
        assertTrue(settings.state.value.keepSearching)
        assertFalse(settings.state.value.keepSearchingWarning)
        settings.setKeepSearching(false)
        assertFalse(graph.appShell.settingsStore.keepSearchingDefault.value)
    }

    @Test
    fun `language switch goes through the per-app locale`() {
        val backend = FakeLocaleBackend()
        val settings = model(DefaultLanguageController(backend, graph.appShell.settingsStore))
        assertEquals(AppLanguage.SYSTEM, settings.state.value.language)
        assertFalse("Android 13+ recreates by itself", settings.setLanguage(AppLanguage.HINDI))
        assertEquals(listOf<String?>("hi"), backend.sets)
        assertEquals(AppLanguage.HINDI, settings.state.value.language)
        settings.setLanguage(AppLanguage.HINDI)
        assertEquals("no-op when unchanged", 1, backend.sets.size)
        settings.setLanguage(AppLanguage.SYSTEM)
        assertEquals(listOf("hi", null), backend.sets)
    }

    @Test
    fun `language controller on Android 13+ uses LocaleManager, before that the stored tag`() {
        val store = SettingsStore(InMemoryKeyValueStore())
        val system = DefaultLanguageController(SystemLocaleBackend(context), store)
        assertEquals(AppLanguage.SYSTEM, system.current())
        assertFalse(system.set(AppLanguage.HINDI))
        val manager = context.getSystemService(android.app.LocaleManager::class.java)
        assertEquals(LocaleList.forLanguageTags("hi"), manager.applicationLocales)
        assertEquals(AppLanguage.HINDI, system.current())
        assertEquals("hi", store.languageTag)

        val legacy = DefaultLanguageController(StoredLocaleBackend(store), store)
        assertTrue("Android 10–12 needs a recreate", legacy.set(AppLanguage.ENGLISH))
        assertEquals(AppLanguage.ENGLISH, legacy.current())
        assertEquals(AppLanguage.HINDI, AppLanguage.fromTag("hi-IN"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("bn"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
    }

    @Test
    fun `legacy locale wrapping applies the stored language on Android 10-12 only`() {
        val saved = java.util.Locale.getDefault()
        try {
            assertSame("nothing stored: unchanged", context, LegacyLocale.wrap(context, sdkInt = 31))
            context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE).edit().putString(SettingsStore.K_LANGUAGE, "hi").commit()
            val wrapped = LegacyLocale.wrap(context, sdkInt = 31)
            assertEquals("hi", wrapped.resources.configuration.locales[0].language)
            assertEquals("आपकी गोपनीयता", wrapped.getString(R.string.onb_notice_title))
            assertEquals("Your privacy", context.getString(R.string.onb_notice_title))
            assertSame("Android 13+ uses the system per-app locale", context, LegacyLocale.wrap(context, sdkInt = 33))
            assertEquals("the chosen language is the process default too", "hi", java.util.Locale.getDefault().language)

            // Back to "Phone's language": nothing stored, and the process default follows the phone again.
            context.getSharedPreferences(SettingsStore.PREFS_NAME, Context.MODE_PRIVATE).edit().remove(SettingsStore.K_LANGUAGE).commit()
            assertSame(context, LegacyLocale.wrap(context, sdkInt = 31, systemLocale = { java.util.Locale.forLanguageTag("en-IN") }))
            assertEquals(java.util.Locale.forLanguageTag("en-IN"), java.util.Locale.getDefault())
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `on Android 10-12 a language change reaches everything built from the application context at once`() {
        val saved = java.util.Locale.getDefault()
        val app = context.applicationContext
        try {
            assertEquals("Prices", app.getString(R.string.compare_title))
            // What the Settings picker runs on Android 10–12 (StoredLocaleBackend's change hook).
            LegacyLocale.applyToProcess(app, "hi", sdkInt = 31)
            assertEquals("hi", app.resources.configuration.locales[0].language)
            // Notifications, toasts, the bubble's status lines and the guardian's texts all use the application.
            assertEquals(
                context.createConfigurationContext(
                    android.content.res.Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("hi")) },
                ).getString(R.string.compare_title),
                app.getString(R.string.compare_title),
            )
            assertEquals("आपकी गोपनीयता", app.getString(R.string.onb_notice_title))
            assertEquals("hi", java.util.Locale.getDefault().language)

            // Android 13+ never gets here (the system per-app locale does it).
            LegacyLocale.applyToProcess(app, "en", sdkInt = 33)
            assertEquals("hi", app.resources.configuration.locales[0].language)

            // Back to the phone's language.
            LegacyLocale.applyToProcess(app, null, sdkInt = 31, systemLocale = { java.util.Locale.ENGLISH })
            assertEquals("Prices", app.getString(R.string.compare_title))
        } finally {
            LegacyLocale.applyToProcess(app, null, sdkInt = 31, systemLocale = { saved })
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `the stored-locale backend applies a change to the running process`() {
        val store = SettingsStore(InMemoryKeyValueStore())
        val changed = mutableListOf<String?>()
        val legacy = DefaultLanguageController(StoredLocaleBackend(store) { changed += it }, store)
        legacy.set(AppLanguage.HINDI)
        legacy.set(AppLanguage.SYSTEM)
        assertEquals(listOf("hi", null), changed)
    }

    @Test
    fun `RideMate's other activities and the session service show the in-app language on Android 10-12`() {
        // ComparisonActivity, CaptureConsentActivity, OpenRideAppActivity and ShareReceiverActivity share this base.
        listOf(
            ComparisonActivity::class.java,
            CaptureConsentActivity::class.java,
            OpenRideAppActivity::class.java,
            ShareReceiverActivity::class.java,
        ).forEach { assertTrue(it.simpleName, LocalizedActivity::class.java.isAssignableFrom(it)) }
        assertEquals(
            "the session service wraps its base context",
            RideSessionService::class.java,
            RideSessionService::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).declaringClass,
        )
        assertEquals(
            RideMateApp::class.java,
            RideMateApp::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).declaringClass,
        )
    }

    @Test
    fun `problem report carries metadata only`() {
        val input = ProblemReport.Input(
            build = BuildInfo(7, "1.2.0", debug = false),
            sdkInt = 37,
            androidRelease = "17",
            signIn = SignInKind.GOOGLE,
            killSwitches = KillSwitches(aiReaderEnabled = false),
            permissions = mapOf(AppPermission.NOTIFICATION_ACCESS to true),
            language = AppLanguage.HINDI,
            onboardingCompleted = true,
            update = UpdateState(),
            developerMode = false,
        )
        val lines = ProblemReport.metadataLines(input)
        assertTrue("app_version: 1.2.0 (7)" in lines)
        assertTrue("android: 17 (API 37)" in lines)
        assertTrue("sign_in: GOOGLE" in lines)
        assertTrue("ai_reader_enabled: false" in lines)
        assertTrue("permission_notification_access: GRANTED" in lines)
        assertTrue("permission_overlay: DENIED" in lines)
        // Values are only versions, booleans and enum names: no emails, ids, places or free text.
        val values = lines.map { it.substringAfter(": ") }
        assertTrue(values.all { it.matches(Regex("^[A-Za-z0-9_.()\\- ]+$")) })
        assertFalse(lines.any { "@" in it })
        assertEquals("RideMate problem report (1.2.0)", ProblemReport.subject(context, input.build))
        val body = ProblemReport.body("Intro", input)
        assertTrue(body.startsWith("Intro\n"))
        assertTrue(body.endsWith(lines.last()))
    }

    @Test
    fun `signing certificate fingerprint is computed from package signing info`() {
        assertEquals(
            // SHA-256 of the bytes 0x01 0x02 0x03.
            "03:90:58:C6:F2:C0:CB:49:2C:53:3B:0A:4D:14:EF:77:CC:0F:78:AB:CC:CE:D5:28:7D:84:A1:A2:01:1C:FB:81",
            SigningCertificate.fingerprint(byteArrayOf(1, 2, 3)),
        )
        // Robolectric reports no signing certificate: the About screen then says it is unavailable (never crashes).
        SigningCertificate.sha256(context).forEach { assertTrue(it.matches(Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$"))) }
    }
}
