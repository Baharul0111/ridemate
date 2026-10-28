package app.ridemate.net

import app.ridemate.auth.IdTokenResult
import app.ridemate.auth.IdTokenSource
import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import app.ridemate.util.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** ApiClient over a local MockWebServer: no real service is contacted. */
class OkHttpApiClientTest {

    private lateinit var server: MockWebServer
    private val clock = object : Clock {
        override fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000
        override fun wallMs(): Long = System.currentTimeMillis()
    }
    private val tokens = FakeTokens()
    private var baseUrl: String? = null
    private var requestIds = 0
    private val calls = mutableListOf<String>()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 9)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun client(timing: ApiTiming = FAST) = OkHttpApiClient(
        http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false).build(),
        baseUrl = { baseUrl },
        tokens = tokens,
        appVersionCode = 42,
        clock = clock,
        timing = timing,
        newRequestId = { "req-${++requestIds}" },
        listener = { endpoint, outcome, attempts, _ -> calls += "${endpoint.name}:$outcome:$attempts" },
    )

    private fun budget(ms: Long = 20_000) = TapBudget(clock.elapsedRealtimeMs() + ms)

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)

    private fun error(code: Int, errorCode: String, extra: String = "") =
        json(code, """{"error":{"code":"$errorCode"$extra,"request_id":"r"}}""")

    private fun readRequest(provider: Provider = Provider.UBER, image: ByteArray = jpeg) =
        ReadScreenRequest(provider, 1080, 2400, image, sessionId = "sess-1")

    // ---------------------------------------------------------------------------------------------------------

    @Test
    fun `read-screen sends every header and the multipart meta plus image`() = runBlocking {
        server.enqueue(
            json(
                200,
                """{"read":{"app":"uber","screen_type":"fare_options","options":[]},"source":"live",
                   "model":"meta-models/Muse-Spark-Open-816B-A42B","latency_ms":2310,"request_id":"rq"}""",
            ),
        )

        val result = client().readScreen(readRequest(), budget())

        val body = (result as ApiResult.Ok).body
        assertEquals("fare_options", body.read["screen_type"]!!.jsonPrimitive.content)
        assertEquals("live", body.source)
        assertEquals("meta-models/Muse-Spark-Open-816B-A42B", body.model)
        assertEquals(2310L, body.latencyMs)

        val request = server.takeRequest()
        assertEquals("/v1/read-screen", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer token-1", request.getHeader("Authorization"))
        assertEquals("req-1", request.getHeader("X-Request-Id"))
        assertEquals("sess-1", request.getHeader("X-Session-Id"))
        assertEquals("42", request.getHeader("X-App-Version"))
        val tapDeadline = request.getHeader("X-Tap-Deadline-Ms")!!.toLong()
        assertTrue("tap deadline $tapDeadline", tapDeadline in 15_000..20_000)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))

        val text = request.body.readUtf8()
        assertTrue(text.contains("Content-Disposition: form-data; name=\"meta\""))
        assertTrue(text.contains("""{"app":"uber","frame_w":1080,"frame_h":2400,"demo":false}"""))
        assertTrue(text.contains("Content-Disposition: form-data; name=\"image\"; filename=\"frame.jpg\""))
        assertTrue(text.contains("Content-Type: image/jpeg"))
        assertEquals(listOf("READ_SCREEN:Ok:1"), calls)
    }

    @Test
    fun `demo screens are flagged and png is detected by magic bytes`() = runBlocking {
        server.enqueue(json(200, """{"read":{"options":[]},"source":"recorded"}"""))

        val result = client().readScreen(readRequest(Provider.DEMO_PEPPER, png), budget())

        assertEquals("recorded", (result as ApiResult.Ok).body.source)
        val text = server.takeRequest().body.readUtf8()
        assertTrue(text.contains(""""app":"demo_pepper""""))
        assertTrue(text.contains(""""demo":true"""))
        assertTrue(text.contains("filename=\"frame.png\""))
        assertTrue(text.contains("Content-Type: image/png"))
    }

    @Test
    fun `reread uploads one crop part per row within the tap budget`() = runBlocking {
        server.enqueue(json(200, """{"rows":[{"row_id":"r1","price_min":110},{"row_id":"r2","price_min":95}],"source":"live"}"""))

        val result = client().reread(
            RereadRequest(Provider.OLA, listOf(RereadCrop("r1", jpeg), RereadCrop("r2", png)), "sess-1"),
            budget(),
        )

        assertEquals(listOf("r1", "r2"), (result as ApiResult.Ok).body.rows.map { it["row_id"]!!.jsonPrimitive.content })
        val request = server.takeRequest()
        assertEquals("/v1/reread", request.path)
        assertTrue(request.getHeader("X-Tap-Deadline-Ms") != null)
        val text = request.body.readUtf8()
        assertTrue(text.contains("""{"app":"ola","rows":[{"row_id":"r1"},{"row_id":"r2"}]}"""))
        assertTrue(text.contains("name=\"crop_r1\"; filename=\"crop_r1.jpg\""))
        assertTrue(text.contains("name=\"crop_r2\"; filename=\"crop_r2.png\""))
    }

    @Test
    fun `point sends meta and image and decodes the box`() = runBlocking {
        server.enqueue(json(200, """{"supported":true,"box_0_1000":[100,200,300,400]}"""))
        val result = client().point(PointRequest(Provider.RAPIDO, jpeg, "sess-1"))
        assertEquals(PointResponse(true, listOf(100, 200, 300, 400)), (result as ApiResult.Ok).body)
        val text = server.takeRequest().body.readUtf8()
        assertTrue(text.contains("""{"app":"rapido","target":"cancel_button"}"""))

        server.enqueue(json(200, """{"supported":false,"box_0_1000":null}"""))
        assertEquals(PointResponse(false, null), (client().point(PointRequest(Provider.RAPIDO, jpeg, "s")) as ApiResult.Ok).body)
    }

    @Test
    fun `images are checked before upload`() = runBlocking {
        val notAnImage = "GIF89a".toByteArray()
        assertEquals(ApiResult.Http(415, "bad_media"), client().readScreen(readRequest(image = notAnImage), budget()))
        val huge = jpeg + ByteArray(ApiLimits.MAX_IMAGE_BYTES)
        assertEquals(ApiResult.Http(413, "too_large"), client().readScreen(readRequest(image = huge), budget()))
        assertEquals(
            ApiResult.Http(413, "too_large"),
            client().reread(RereadRequest(Provider.UBER, listOf(RereadCrop("a", huge)), "s"), budget()),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `json endpoints send the contract bodies`() = runBlocking {
        server.enqueue(json(200, """{"label":"assigned","evidence_span":"captain is on the way","request_id":"x"}"""))
        val parse = client().parseNotification(
            ParseNotificationRequest(Provider.RAPIDO, 1234, "<m>", "captain is on the way", "ride_updates", "status", true, RideState.SEARCHING),
            sessionId = "sess-9",
        )
        assertEquals("assigned", (parse as ApiResult.Ok).body.label)
        val parseRequest = server.takeRequest()
        assertEquals("/v1/parse-notification", parseRequest.path)
        assertEquals("sess-9", parseRequest.getHeader("X-Session-Id"))
        assertNull(parseRequest.getHeader("X-Tap-Deadline-Ms"))
        val parseBody = Json.parseToJsonElement(parseRequest.body.readUtf8()).jsonObject
        assertEquals("rapido", parseBody["app"]!!.jsonPrimitive.content)
        assertEquals(1234, parseBody["version_code"]!!.jsonPrimitive.int)
        assertEquals("SEARCHING", parseBody["leg_state"]!!.jsonPrimitive.content)
        assertEquals("true", parseBody["is_ongoing"]!!.jsonPrimitive.content)
        assertEquals("ride_updates", parseBody["channel_id"]!!.jsonPrimitive.content)

        server.enqueue(json(200, """{"pick":"none"}"""))
        val pick = client().placePick(PlacePickRequest("airport", "hi-IN", listOf(PlaceCandidate("c1", "LGBI Airport"))), null)
        assertNull((pick as ApiResult.Ok).body.pick)
        assertEquals(
            """{"transcript":"airport","lang":"hi-IN","candidates":[{"id":"c1","label":"LGBI Airport"}]}""",
            server.takeRequest().body.readUtf8(),
        )

        server.enqueue(MockResponse().setResponseCode(202))
        val report = SessionEventReport("sess-2", "uber", 1, "confirmed_all", "1_3m", 34, 4, 9, 1, 6, 1, 0, false)
        assertEquals(ApiResult.Ok(Unit), client().event(report))
        val eventRequest = server.takeRequest()
        assertEquals("sess-2", eventRequest.getHeader("X-Session-Id"))
        val eventBody = Json.parseToJsonElement(eventRequest.body.readUtf8()).jsonObject
        assertEquals("1_3m", eventBody["time_to_confirm_bucket"]!!.jsonPrimitive.content)
        assertEquals(9, eventBody["verified_rows"]!!.jsonPrimitive.int)

        server.enqueue(json(200, """{"erased":true}"""))
        assertEquals(ApiResult.Ok(EraseResponse(true)), client().erase())
        assertEquals("/v1/erase", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(ApiResult.Ok(Unit), client().registerOwnerFcmToken("fcm"))
        assertEquals("""{"token":"fcm"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `config-check decodes the owner budget`() = runBlocking {
        server.enqueue(
            json(
                200,
                """{"ai_enabled":false,"reason":"budget","resume_at_ist":"13:30","point_supported":false,
                   "quota":{"reads_left":6,"parses_left":10},"cap_profile":"normal","min_supported_version":1,
                   "server_time_utc":"2026-10-28T10:00:00Z","new_field":1,
                   "budget":{"ai_spent_usd":1.23,"ai_budget_usd":80,"day_cap_usd":1.3,"horizon_end":"2027-01-26"}}""",
            ),
        )
        val body = (client().configCheck() as ApiResult.Ok).body
        assertEquals(false, body.aiEnabled)
        assertEquals("budget", body.reason)
        assertEquals(6, body.quota!!.readsLeft)
        assertEquals(80.0, body.budget!!.aiBudgetUsd!!, 0.0)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/config-check", request.path)
    }

    @Test
    fun `ondc endpoints carry txn and tap ids`() = runBlocking {
        server.enqueue(json(200, """{"txn_id":"t-1"}"""))
        assertEquals("t-1", (client().ondcSearch("sess", LatLng(26.1, 91.6), LatLng(26.2, 91.7)) as ApiResult.Ok).body.txnId)
        assertEquals(
            """{"session_id":"sess","pickup":{"lat":26.1,"lng":91.6},"drop":{"lat":26.2,"lng":91.7}}""",
            server.takeRequest().body.readUtf8(),
        )
        val ok = """{"ok":true}"""
        repeat(5) { server.enqueue(json(200, ok)) }
        assertEquals(ApiResult.Ok(Unit), client().ondcSelect("t-1", "item-1"))
        assertEquals("""{"txn_id":"t-1","item_id":"item-1"}""", server.takeRequest().body.readUtf8())
        assertEquals(ApiResult.Ok(Unit), client().ondcInit("t-1"))
        assertEquals("""{"txn_id":"t-1"}""", server.takeRequest().body.readUtf8())
        assertEquals(ApiResult.Ok(Unit), client().ondcConfirm("t-1", "tap-1"))
        assertEquals("""{"txn_id":"t-1","tap_id":"tap-1"}""", server.takeRequest().body.readUtf8())
        assertEquals(ApiResult.Ok(Unit), client().ondcStatus("t-1"))
        assertEquals("/v1/ondc/status", server.takeRequest().path)
        assertEquals(ApiResult.Ok(Unit), client().ondcCancel("t-1", "tap-2", confirm = false))
        assertEquals("""{"txn_id":"t-1","tap_id":"tap-2","confirm":false}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `health checks need no token`() = runBlocking {
        tokens.signedOut = true
        server.enqueue(json(200, """{"ok":true}"""))
        assertEquals(ApiResult.Ok(true), client().healthz())
        assertNull(server.takeRequest().getHeader("Authorization"))
        server.enqueue(json(200, """{"ok":true,"checks":{"dict":"ok"}}"""))
        assertEquals("ok", ((client().readyz() as ApiResult.Ok).body["checks"] as JsonObject)["dict"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no base url means not configured and no request`() = runBlocking {
        baseUrl = ""
        assertEquals(ApiResult.NotConfigured, client().configCheck())
        baseUrl = "not a url"
        assertEquals(ApiResult.NotConfigured, client().readScreen(readRequest(), budget()))
        baseUrl = null
        assertEquals(ApiResult.NotConfigured, client().erase())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `missing token means auth needed or offline without a request`() = runBlocking {
        tokens.signedOut = true
        assertEquals(ApiResult.AuthNeeded, client().configCheck())
        tokens.signedOut = false
        tokens.networkError = true
        assertEquals(ApiResult.Offline, client().configCheck())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `every error code maps to its phone behaviour`() = runBlocking {
        val cases = listOf(
            error(403, "google_required") to ApiResult.GoogleRequired,
            error(403, "blocked") to ApiResult.AiPaused("blocked", null, null),
            error(403, "forbidden") to ApiResult.Http(403, "forbidden"),
            error(404, "not_found") to ApiResult.Http(404, "not_found"),
            error(413, "too_large") to ApiResult.Http(413, "too_large"),
            error(415, "bad_media") to ApiResult.Http(415, "bad_media"),
            error(422, "invalid") to ApiResult.Http(422, "invalid"),
            error(429, "rate_limited", ""","retry_after_s":30""") to ApiResult.RateLimited(30),
            error(429, "quota_exhausted", ""","resume_at_ist":"13:30"""") to ApiResult.AiPaused("quota_exhausted", "13:30", null),
            error(429, "ai_paused", ""","resume_at_ist":"07:00","reason":"day_cap"""") to ApiResult.AiPaused("ai_paused", "07:00", "day_cap"),
            error(503, "ai_unavailable") to ApiResult.AiPaused("ai_unavailable", null, null),
            error(503, "meter_unavailable") to ApiResult.AiPaused("meter_unavailable", null, null),
            error(503, "ondc_unavailable") to ApiResult.Http(503, "ondc_unavailable"),
            error(504, "model_timeout") to ApiResult.Timeout,
        )
        for ((response, expected) in cases) {
            server.enqueue(response)
            assertEquals(response.status, expected, client().configCheck())
        }
        // None of these definitive answers was retried.
        assertEquals(cases.size, server.requestCount)
    }

    @Test
    fun `401 refreshes the token once and reuses the request id`() = runBlocking {
        server.enqueue(error(401, "auth_invalid"))
        server.enqueue(json(200, """{"erased":true}"""))

        assertEquals(ApiResult.Ok(EraseResponse(true)), client().erase())

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("Bearer token-1", first.getHeader("Authorization"))
        assertEquals("Bearer token-2", second.getHeader("Authorization"))
        assertEquals(first.getHeader("X-Request-Id"), second.getHeader("X-Request-Id"))
        assertEquals(listOf(false, true), tokens.forceFlags)
    }

    @Test
    fun `a second 401 means auth needed`() = runBlocking {
        server.enqueue(error(401, "auth_required"))
        server.enqueue(error(401, "auth_invalid"))
        assertEquals(ApiResult.AuthNeeded, client().erase())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `transient failures are retried once with the same request id`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(json(200, """{"ai_enabled":true}"""))
        val started = clock.elapsedRealtimeMs()

        val result = client().configCheck()

        assertEquals(true, (result as ApiResult.Ok).body.aiEnabled)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals(first.getHeader("X-Request-Id"), second.getHeader("X-Request-Id"))
        assertTrue("jitter waited", clock.elapsedRealtimeMs() - started >= FAST.jitterMinMs)
        assertEquals(listOf("CONFIG_CHECK:Ok:2"), calls)
    }

    @Test
    fun `only one retry is ever made`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(502)) }
        assertEquals(ApiResult.Http(502, null), client().configCheck())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `no retry when the remaining budget does not exceed the server deadline`() = runBlocking {
        // Phone deadline 950 ms, server deadline 900 ms, jitter 100 ms: after the jitter < 900 ms would remain.
        val tight = ApiTiming(
            phoneMarginMs = 50,
            jitterMinMs = 100,
            jitterMaxMs = 100,
            serverDeadlineOverridesMs = mapOf(Endpoint.CONFIG_CHECK to 900L),
        )
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(json(200, "{}"))
        assertEquals(ApiResult.Http(503, null), client(tight).configCheck())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `read-screen retries only while the tap budget exceeds the 12 s server deadline`() = runBlocking {
        val production = FAST.copy(serverDeadlineOverridesMs = emptyMap())
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(json(200, """{"read":{"options":[]}}"""))
        assertTrue(client(production).readScreen(readRequest(), budget(20_000)) is ApiResult.Ok)
        assertEquals(2, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(json(200, """{"read":{"options":[]}}"""))
        // Only 8 s left of the tap: a retry could not finish within the server's 12 s, so none is made.
        assertEquals(ApiResult.Http(500, null), client(production).readScreen(readRequest(), budget(8_000)))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `the tap deadline bounds the whole call`() = runBlocking {
        server.enqueue(json(200, """{"read":{"options":[]}}""").setHeadersDelay(3, TimeUnit.SECONDS))
        val started = clock.elapsedRealtimeMs()

        val result = client().readScreen(readRequest(), budget(400))

        assertEquals(ApiResult.Timeout, result)
        assertTrue("returned at the deadline", clock.elapsedRealtimeMs() - started < 2_000)
        val sentDeadline = server.takeRequest().getHeader("X-Tap-Deadline-Ms")!!.toLong()
        assertTrue(sentDeadline in 1..400)
    }

    @Test
    fun `an exhausted tap budget fails before sending`() = runBlocking {
        assertEquals(ApiResult.Timeout, client().readScreen(readRequest(), TapBudget(clock.elapsedRealtimeMs() - 1)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `per-endpoint phone deadlines apply to json calls`() = runBlocking {
        // Server deadline 300 ms + margin 200 ms = 500 ms phone deadline.
        val timing = FAST.copy(phoneMarginMs = 200, serverDeadlineOverridesMs = mapOf(Endpoint.PARSE_NOTIFICATION to 300L))
        server.enqueue(json(200, """{"label":"other"}""").setBodyDelay(3, TimeUnit.SECONDS))
        val started = clock.elapsedRealtimeMs()
        val result = client(timing).parseNotification(
            ParseNotificationRequest(Provider.UBER, 1, null, null, null, null, false, RideState.ASSIGNED),
            "s",
        )
        assertEquals(ApiResult.Timeout, result)
        assertTrue(clock.elapsedRealtimeMs() - started < 2_000)
    }

    @Test
    fun `an unreachable server is offline after one retry`() = runBlocking {
        server.shutdown()
        assertEquals(ApiResult.Offline, client().configCheck())
        assertEquals(listOf("CONFIG_CHECK:Offline:2"), calls)
    }

    @Test
    fun `an undecodable success body is reported as malformed`() = runBlocking {
        server.enqueue(json(200, """{"no_read_here":true}"""))
        assertEquals(ApiResult.Http(200, OkHttpApiClient.MALFORMED), client().readScreen(readRequest(), budget()))
        server.enqueue(json(200, "not json"))
        assertEquals(ApiResult.Http(200, OkHttpApiClient.MALFORMED), client().configCheck())
    }

    @Test
    fun `phone deadlines follow the architecture`() {
        val t = ApiTiming.PRODUCTION
        assertEquals(17_000, t.phoneDeadlineMs(Endpoint.READ_SCREEN))
        assertEquals(11_000, t.phoneDeadlineMs(Endpoint.PARSE_NOTIFICATION))
        assertEquals(11_000, t.phoneDeadlineMs(Endpoint.PLACE_PICK))
        assertEquals(14_000, t.phoneDeadlineMs(Endpoint.POINT))
        Endpoint.entries.filter { it.serverDeadlineMs == 5_000L }.forEach { assertEquals(10_000, t.phoneDeadlineMs(it)) }
        assertEquals(20_000, TapBudget.TOTAL_MS)
        assertEquals(1_000, t.jitterMinMs)
        assertEquals(2_000, t.jitterMaxMs)
    }

    /** Tokens "token-1", "token-2", …; records each call's forceRefresh flag. */
    private class FakeTokens : IdTokenSource {
        var signedOut = false
        var networkError = false
        var counter = 0
        val forceFlags = mutableListOf<Boolean>()

        override suspend fun idToken(forceRefresh: Boolean): IdTokenResult {
            forceFlags += forceRefresh
            return when {
                signedOut -> IdTokenResult.SignedOut
                networkError -> IdTokenResult.NetworkError
                else -> IdTokenResult.Token("token-${++counter}", Long.MAX_VALUE)
            }
        }
    }

    companion object {
        /** Production deadlines with a 10–20 ms jitter so retry tests run fast. */
        val FAST = ApiTiming(jitterMinMs = 10, jitterMaxMs = 20)
    }
}
