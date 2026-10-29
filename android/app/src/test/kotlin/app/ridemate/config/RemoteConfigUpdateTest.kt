package app.ridemate.config

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.compare.RemoteFareReadingConfig
import app.ridemate.core.config.ConfigJson
import app.ridemate.core.fare.FareParser
import app.ridemate.core.model.Provider
import app.ridemate.core.config.NotificationEvent
import app.ridemate.core.watch.PostSnapshot
import app.ridemate.core.watch.TemplateEngine
import app.ridemate.crash.CrashReporter
import app.ridemate.session.RemoteGuardianConfig
import app.ridemate.testing.FakeCrashBackend
import app.ridemate.testing.FakeRemoteConfigBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Goal 22: a pushed template or parser update changes how the running app reads notifications and screens, within
 * the real-time update and with no restart, through the very config sources production uses
 * ([RemoteGuardianConfig], [RemoteFareReadingConfig]); an invalid push keeps the last good payload.
 */
@RunWith(AndroidJUnit4::class)
class RemoteConfigUpdateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: SharedPreferences
    private lateinit var scope: CoroutineScope
    private val backend = FakeRemoteConfigBackend()
    private val bundled: Map<String, String> by lazy { AssetBundledConfig(context.assets).load() }

    @Before
    fun setUp() {
        prefs = context.getSharedPreferences("remote-update-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() = scope.cancel()

    private fun repo() = ConfigRepository(backend, { bundled }, prefs, scope, CrashReporter { FakeCrashBackend() }, minimumFetchIntervalSeconds = 3600)

    private fun post(text: String) = PostSnapshot(
        packageName = Provider.UBER.packageName, sbnKey = "u1", id = 1, tag = null, channelId = "ride", category = "status",
        isOngoing = true, title = "Uber", text = text, postTimeMs = 1_793_000_000_000L, versionCode = 100L,
    )

    /** Pushes [value] for [key] as the real-time stream does, and waits until the app has applied it. */
    private suspend fun push(repo: ConfigRepository, key: String, value: String) {
        val before = repo.epoch.value
        backend.fetched[key] = value
        backend.publishUpdate(setOf(key))
        withTimeout(5_000) { repo.epoch.first { it > before } }
    }

    private fun bundledJson(key: String): JsonObject = ConfigJson.parseToJsonElement(bundled.getValue(key)).jsonObject

    @Test
    fun `a pushed notification template is used for the next post, and a broken push keeps the last good one`() = runBlocking {
        val repo = repo()
        repo.start()
        withTimeout(5_000) { while (backend.fetchCount == 0) delay(5) }
        val guardianConfig = RemoteGuardianConfig(repo) { "en" }
        val wording = post("Your moto captain Ravi is here")

        fun hit() = guardianConfig.templates().entryFor(Provider.UBER.packageName, 100L)?.let { TemplateEngine.match(it.entry, wording, null) }
        assertNull("the bundled templates do not know this wording (a MAYBE prompt would ask)", hit())

        // The owner records the wording and pushes a template for it.
        val base = bundledJson(ConfigKeys.NOTIFICATION_TEMPLATES)
        val uberEntries = base.getValue("apps").jsonObject.getValue(Provider.UBER.packageName).jsonArray
        val first = uberEntries[0].jsonObject
        val added = JsonObject(
            mapOf(
                "id" to JsonPrimitive("uber.moto_here.v2"),
                "event" to JsonPrimitive("ARRIVING"),
                "pattern" to JsonPrimitive("(?i)your moto captain (?:\\w+ )?is here"),
                "channel_id" to kotlinx.serialization.json.JsonNull,
                "ongoing" to kotlinx.serialization.json.JsonNull,
            ),
        )
        val newFirst = JsonObject(first + ("templates" to JsonArray(listOf(added) + first.getValue("templates").jsonArray)))
        val apps = JsonObject(base.getValue("apps").jsonObject + (Provider.UBER.packageName to JsonArray(listOf(newFirst) + uberEntries.drop(1))))
        push(repo, ConfigKeys.NOTIFICATION_TEMPLATES, JsonObject(base + ("apps" to apps)).toString())

        val after = hit()
        assertNotNull("the same wording is now a deterministic event, without a restart", after)
        assertEquals("uber.moto_here.v2", after!!.templateId)
        assertEquals(NotificationEvent.ARRIVING, after.event)

        // A broken push never replaces what works.
        push(repo, ConfigKeys.NOTIFICATION_TEMPLATES, "{ not json")
        assertEquals("uber.moto_here.v2", hit()?.templateId)
    }

    @Test
    fun `a pushed fare parser for a new app version is used on the next Read fares`() = runBlocking {
        val repo = repo()
        repo.start()
        withTimeout(5_000) { while (backend.fetchCount == 0) delay(5) }
        val fareConfig = RemoteFareReadingConfig(repo)
        val newVersion = 9_000_000L
        assertEquals(0L, fareConfig.fareParsers()!!.entryFor(Provider.UBER.packageName, newVersion)!!.entry.minVersionCode)

        // Uber's new version changed its screen: the owner pushes a parser for it.
        val base = bundledJson(ConfigKeys.FARE_PARSERS)
        val uberEntries = base.getValue("apps").jsonObject.getValue(Provider.UBER.packageName).jsonArray
        val forNewVersion = JsonObject(uberEntries[0].jsonObject + ("min_version_code" to JsonPrimitive(newVersion)))
        val apps = JsonObject(base.getValue("apps").jsonObject + (Provider.UBER.packageName to JsonArray(uberEntries + forNewVersion)))
        push(repo, ConfigKeys.FARE_PARSERS, JsonObject(base + ("apps" to apps)).toString())

        val picked = fareConfig.fareParsers()!!.entryFor(Provider.UBER.packageName, newVersion)!!
        assertEquals(newVersion, picked.entry.minVersionCode)
        assertNotNull(FareParser.forApp(fareConfig.fareParsers()!!, Provider.UBER, newVersion))
        // Older installs keep their own parser.
        assertEquals(0L, fareConfig.fareParsers()!!.entryFor(Provider.UBER.packageName, 100L)!!.entry.minVersionCode)

        push(repo, ConfigKeys.FARE_PARSERS, """{"schema":"1.0","apps":{"com.ubercab":[]}}""")
        assertEquals("an invalid push keeps the last good parsers", newVersion, fareConfig.fareParsers()!!.entryFor(Provider.UBER.packageName, newVersion)!!.entry.minVersionCode)
    }
}
