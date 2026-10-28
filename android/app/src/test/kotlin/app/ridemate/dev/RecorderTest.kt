package app.ridemate.dev

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ridemate.auth.AuthState
import app.ridemate.data.RecorderPostEntity
import app.ridemate.data.RideMateDb
import app.ridemate.testing.FakeClock
import app.ridemate.testing.TestSeams
import app.ridemate.ui.InMemoryKeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecorderTest {
    private lateinit var db: RideMateDb
    private val auth = MutableStateFlow<AuthState>(AuthState.Google("owner@example.com", "Owner", null, "o"))
    private val clock = FakeClock()
    private val gate = DeveloperModeGate(InMemoryKeyValueStore(), auth, { setOf(OwnerEmails.hash("owner@example.com")) }, clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        db = TestSeams.inMemoryDb(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    private fun sink() = RoomRecorderSink({ db.recorderPostDao() }, gate, scope, Dispatchers.Unconfined, null)

    private fun post(pkg: String = "com.rapido.passenger", removal: Int? = null) = RecordedPost(
        packageName = pkg,
        channelId = "ride_status",
        category = "status",
        isOngoing = true,
        notificationId = 7,
        tag = "ride-9876543210",
        title = "Captain Ravi Kumar is arriving",
        text = "Call 98765 43210 · OTP 4821 · KA 01 AB 1234 · ₹120",
        redacted = false,
        removalReason = removal,
        atUtcMs = 1_793_000_000_000L,
    )

    private fun enable() {
        repeat(DeveloperModeGate.TAPS_TO_ENABLE) {
            clock.advance(100)
            gate.registerVersionTap()
        }
        assertTrue(gate.isEnabled())
    }

    private suspend fun rows(expected: Int): List<RecorderPostEntity> = withTimeout(5_000) {
        while (db.recorderPostDao().count() < expected) delay(10)
        db.recorderPostDao().all()
    }

    @Test
    fun `nothing is recorded unless developer mode and the recorder are on`() = runBlocking {
        val sink = sink()
        assertFalse(sink.isRecording)
        sink.record(post())
        enable()
        gate.setRecorderOn(false)
        sink.record(post())
        assertEquals(0, db.recorderPostDao().count())
        gate.setRecorderOn(true)
        assertTrue(sink.isRecording)
        sink.record(post())
        assertEquals(1, rows(1).size)
    }

    @Test
    fun `posts are masked before storage and other packages are dropped unread`() = runBlocking {
        enable()
        val sink = sink()
        sink.record(post(pkg = "com.whatsapp"))
        sink.record(post())
        sink.record(post(removal = 8))
        val stored = rows(2)
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.packageName == "com.rapido.passenger" })
        val row = stored.first()
        val text = row.textMasked!!
        assertFalse("phone number masked", "98765" in text)
        assertFalse("OTP masked", "4821" in text)
        assertFalse("plate masked", "1234" in text)
        assertTrue("prices stay", "₹120" in text)
        assertFalse("driver name masked", "Ravi" in row.titleMasked!!)
        assertFalse("tag masked", "9876543210" in row.tag!!)
        assertEquals(8, stored.last().removalReason)
    }

    @Test
    fun `export JSON has the documented shape and stays masked`() {
        val rows = listOf(
            RecorderMasking.toEntity(post()),
            RecorderMasking.toEntity(post(removal = 8)),
            // A row written by an older build without masking is masked again on export.
            RecorderPostEntity(
                packageName = "com.ubercab", channelId = null, category = null, isOngoing = false, notifId = 1, tag = null,
                titleMasked = "Your OTP is 5512", textMasked = null, redacted = true, removalReason = null, atUtc = 1_793_000_100_000L,
            ),
            RecorderPostEntity(
                packageName = "com.example.other", channelId = null, category = null, isOngoing = false, notifId = 1, tag = null,
                titleMasked = "x", textMasked = null, redacted = false, removalReason = null, atUtc = 1L,
            ),
        )
        val json = RecorderExport.toJson(RecorderExport.build(rows, 1_793_000_200_000L, 1, "1.0.0"))
        val doc = Json.parseToJsonElement(json).jsonObject
        assertEquals("1.0", doc["schema"]!!.jsonPrimitive.content)
        assertEquals("recorder_posts", doc["kind"]!!.jsonPrimitive.content)
        assertEquals("2026-10-26T07:36:40Z", doc["exported_at_utc"]!!.jsonPrimitive.content)
        val posts: JsonArray = doc["posts"]!!.jsonArray
        assertEquals("non-allowlisted rows are left out", 3, posts.size)
        val keys = setOf(
            "package", "version_code", "channel_id", "category", "is_ongoing", "id", "tag", "title", "text",
            "redacted", "removal_reason", "removal_reason_name", "posted_at", "at_ms", "label",
        )
        posts.forEach { assertEquals(keys, (it as JsonObject).keys) }
        val first = posts[0].jsonObject
        assertEquals(JsonNull, first["removal_reason"])
        assertEquals(JsonNull, first["label"])
        val removed = posts[1].jsonObject
        assertEquals("REASON_APP_CANCEL", removed["removal_reason_name"]!!.jsonPrimitive.content)
        assertFalse("5512" in posts[2].jsonObject["title"]!!.jsonPrimitive.content)
        assertTrue("redacted flag kept", posts[2].jsonObject["redacted"]!!.jsonPrimitive.content == "true")
        listOf("98765", "4821", "Ravi", "9876543210", "5512").forEach { assertFalse("$it leaked into the export", it in json) }
    }
}
