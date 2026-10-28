package app.ridemate.config

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.core.config.ConfigJson
import app.ridemate.core.config.Layer
import app.ridemate.core.model.Provider
import app.ridemate.crash.CrashReporter
import app.ridemate.testing.FakeCrashBackend
import app.ridemate.testing.FakeRemoteConfigBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: SharedPreferences
    private lateinit var scope: CoroutineScope
    private val backend = FakeRemoteConfigBackend()
    private val crash = FakeCrashBackend()
    private var bundled = mapOf<String, String>()

    private val templatesV1 = """{"schema":"1.0","apps":{}}"""
    private val templatesV2 = """{"schema":"2.0","apps":{}}"""

    @Before
    fun setUp() {
        prefs = context.getSharedPreferences("config-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() = scope.cancel()

    private fun repo() = ConfigRepository(backend, { bundled }, prefs, scope, CrashReporter { crash }, minimumFetchIntervalSeconds = 3600)

    private fun parseTemplates(raw: String): JsonObject = ConfigJson.parseToJsonElement(raw) as JsonObject

    @Test
    fun `start configures a 5 s fetch and never waits for it`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        backend.fetchGate = gate
        val repo = repo()

        val started = repo.start()
        assertTrue(repo.start() === started) // idempotent

        withTimeout(5_000) { while (backend.fetchCount == 0) kotlinx.coroutines.delay(5) }
        // The settings were applied before the first fetch, which is still in flight.
        assertEquals(5L, backend.configuredTimeoutS)
        assertEquals(3600L, backend.configuredMinIntervalS)
        assertEquals(1, backend.listenerCount())
        // Reads answer immediately from bundled defaults while the fetch is still in flight.
        assertEquals(true, repo.aiReaderEnabled.value)
        assertEquals("", repo.apiBaseUrl())
        backend.fetched[ConfigKeys.API_BASE_URL] = "https://ridemate.modal.run"
        gate.complete(Unit)
        withTimeout(5_000) { repo.epoch.first { repo.apiBaseUrl().isNotEmpty() } }
        assertEquals("https://ridemate.modal.run", repo.apiBaseUrl())
        assertEquals(1, backend.fetchCount)
    }

    @Test
    fun `overlapping refreshes are coalesced and failures keep serving defaults`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        backend.fetchGate = gate
        val repo = repo()
        val first = repo.refreshInBackground()
        val second = repo.refreshInBackground()
        assertTrue(first === second)
        backend.fetchFails = true
        gate.complete(Unit)
        first.join()
        assertEquals(1, backend.fetchCount)
        assertEquals(true, repo.ondcEnabled.value)
        assertEquals(FetchStatus.FAILURE, repo.info().status)
        assertTrue(crash.events.contains("log:CONFIG"))
    }

    @Test
    fun `kill switches update in real time`() = runBlocking {
        val repo = repo()
        repo.start()
        withTimeout(5_000) { while (backend.fetchCount == 0) kotlinx.coroutines.delay(5) }
        assertTrue(repo.killSwitches.value.aiReaderEnabled)

        backend.fetched[ConfigKeys.AI_READER_ENABLED] = "false"
        backend.fetched[ConfigKeys.readerEnabled(Provider.OLA)] = "false"
        backend.fetched[ConfigKeys.ONDC_ENABLED] = "false"
        backend.fetched[ConfigKeys.DEMO_ENABLED] = "false"
        backend.publishUpdate(setOf(ConfigKeys.AI_READER_ENABLED))

        withTimeout(5_000) { repo.aiReaderEnabled.first { !it } }
        assertFalse(repo.readerEnabled(Provider.OLA).value)
        assertTrue(repo.readerEnabled(Provider.UBER).value)
        assertFalse(repo.ondcEnabled.value)
        assertFalse(repo.demoEnabled.value)
        assertFalse(repo.readerEnabled(Provider.DEMO_KESTREL).value)
        assertFalse(repo.readerEnabled(Provider.ONDC).value)
        assertFalse(repo.killSwitches.value.readerEnabledFor(Provider.OLA))
    }

    @Test
    fun `bundled assets override built-in scalars and invalid remote scalars fall back`() {
        bundled = mapOf(ConfigKeys.MIN_SUPPORTED_VERSION to "3", ConfigKeys.API_BASE_URL to "https://bundled.example")
        val repo = repo()
        assertEquals(3, repo.minSupportedVersion())
        assertEquals("https://bundled.example", repo.apiBaseUrl())
        assertEquals("normal", repo.string(ConfigKeys.CAP_PROFILE))
        assertEquals(90L, repo.long(ConfigKeys.BUDGET_HORIZON_DAYS, 0))

        backend.active[ConfigKeys.MIN_SUPPORTED_VERSION] = "not-a-number"
        backend.active[ConfigKeys.AI_READER_ENABLED] = "maybe"
        assertEquals(3, repo.minSupportedVersion())
        assertTrue(repo.boolean(ConfigKeys.AI_READER_ENABLED, false))
        backend.active[ConfigKeys.MIN_SUPPORTED_VERSION] = "7"
        assertEquals(7, repo.minSupportedVersion())
    }

    @Test
    fun `raw returns the three layers`() {
        bundled = mapOf(ConfigKeys.NOTIFICATION_TEMPLATES to templatesV1)
        val repo = repo()
        assertEquals(RawConfig(null, null, templatesV1), repo.raw(ConfigKeys.NOTIFICATION_TEMPLATES))
        backend.active[ConfigKeys.NOTIFICATION_TEMPLATES] = templatesV2
        prefs.edit().putString(ConfigKeys.NOTIFICATION_TEMPLATES, templatesV1).commit()
        assertEquals(RawConfig(templatesV2, templatesV1, templatesV1), repo.raw(ConfigKeys.NOTIFICATION_TEMPLATES))
        assertEquals(RawConfig(null, null, null), repo.raw("no_such_key"))
    }

    @Test
    fun `last-good is written only when the validator accepts`() {
        val repo = repo()
        assertFalse(repo.acceptLastGood("k", "bad") { false })
        assertFalse(repo.acceptLastGood("k", "boom") { error("parse failed") })
        assertNull(repo.raw("k").lastGood)
        assertTrue(repo.acceptLastGood("k", "good") { it == "good" })
        assertEquals("good", repo.raw("k").lastGood)
    }

    @Test
    fun `resolve prefers a valid remote payload and remembers it as last-good`() {
        bundled = mapOf(ConfigKeys.NOTIFICATION_TEMPLATES to templatesV1)
        val repo = repo()
        val remoteV1 = """{"schema":"1.3","apps":{"com.ubercab":[]}}"""
        backend.active[ConfigKeys.NOTIFICATION_TEMPLATES] = remoteV1

        val first = repo.resolve(ConfigKeys.NOTIFICATION_TEMPLATES, ::parseTemplates)!!
        assertEquals(Layer.REMOTE, first.source)
        assertEquals(remoteV1, prefs.getString(ConfigKeys.NOTIFICATION_TEMPLATES, null))

        // A breaking remote payload (unknown schema major) is ignored: the last good one wins.
        backend.active[ConfigKeys.NOTIFICATION_TEMPLATES] = templatesV2
        val second = repo.resolve(ConfigKeys.NOTIFICATION_TEMPLATES, ::parseTemplates)!!
        assertEquals(Layer.LAST_GOOD, second.source)
        assertEquals(remoteV1, second.raw)

        // Garbage remote and no last-good: bundled.
        prefs.edit().clear().commit()
        backend.active[ConfigKeys.NOTIFICATION_TEMPLATES] = "{not json"
        assertEquals(Layer.BUNDLED, repo.resolve(ConfigKeys.NOTIFICATION_TEMPLATES, ::parseTemplates)!!.source)
        assertNull(prefs.getString(ConfigKeys.NOTIFICATION_TEMPLATES, null))
    }

    @Test
    fun `resolve without any valid layer is null`() {
        val repo = repo()
        assertNull(repo.resolve("fare_parsers_v9", ::parseTemplates))
        backend.active["fare_parsers_v9"] = templatesV1
        assertEquals(Layer.REMOTE, repo.resolve("fare_parsers_v9", ::parseTemplates)!!.source)
    }

    @Test
    fun `latest version is decoded and validated`() {
        val repo = repo()
        val bundledLatest = repo.latestVersion()!!
        assertEquals(1, bundledLatest.versionCode)
        assertEquals("1.0.0-hackathon", bundledLatest.versionName)

        backend.active[ConfigKeys.LATEST_VERSION] =
            """{"schema":"1.0","version_code":5,"version_name":"1.1.0","url":"https://github.com/Baharul0111/ridemate/releases/tag/v1.1.0","sha256":"ab"}"""
        assertEquals(5, repo.latestVersion()!!.versionCode)
        backend.active[ConfigKeys.LATEST_VERSION] = """{"schema":"1.0","version_name":"no code"}"""
        // Invalid remote: the last good (5) is kept.
        assertEquals(5, repo.latestVersion()!!.versionCode)
    }

    @Test
    fun `info fingerprints the active remote values`() {
        val repo = repo()
        assertNull(repo.info().remoteFingerprint)
        backend.active["a"] = "1"
        val one = repo.info().remoteFingerprint
        assertNotNull(one)
        backend.active["a"] = "2"
        assertTrue(one != repo.info().remoteFingerprint)
        assertEquals(12, one!!.length)
    }

    @Test
    fun `bundled asset values decode json strings and keep other json as text`() {
        assertEquals("https://x.modal.run", AssetBundledConfig.decodeBundledValue("\"https://x.modal.run\"\n"))
        assertEquals("", AssetBundledConfig.decodeBundledValue("\"\""))
        assertEquals("true", AssetBundledConfig.decodeBundledValue(" true "))
        assertEquals("1", AssetBundledConfig.decodeBundledValue("1"))
        assertEquals("""{"schema":"1.0"}""", AssetBundledConfig.decodeBundledValue("""{"schema":"1.0"}"""))
        assertEquals("plain text", AssetBundledConfig.decodeBundledValue("plain text"))
        val bom = Char(0xFEFF)
        assertEquals("https://bom.example", AssetBundledConfig.decodeBundledValue("$bom \"https://bom.example\"\n"))
        // A missing assets/config folder is just "no bundled defaults".
        assertEquals(emptyMap<String, String>(), AssetBundledConfig(context.assets, "no-such-dir").load())
    }

    @Test
    fun `parse boolean accepts common spellings`() {
        assertEquals(true, ConfigRepository.parseBoolean("TRUE"))
        assertEquals(false, ConfigRepository.parseBoolean("0"))
        assertNull(ConfigRepository.parseBoolean(null))
        assertNull(ConfigRepository.parseBoolean("x"))
        assertEquals(
            "true",
            (ConfigJson.parseToJsonElement(BuiltInDefaults.VALUES.getValue(ConfigKeys.MODEL_ROUTE)) as JsonObject)["schema"]!!
                .jsonPrimitive.content.let { if (it == "1.0") "true" else "false" },
        )
    }
}
