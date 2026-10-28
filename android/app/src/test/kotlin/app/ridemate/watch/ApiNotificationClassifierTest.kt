package app.ridemate.watch

import app.ridemate.auth.AuthState
import app.ridemate.core.model.RideState
import app.ridemate.core.watch.ClassifierLabel
import app.ridemate.core.watch.ClassifierResult
import app.ridemate.core.watch.ClassifyRequest
import app.ridemate.core.watch.UnavailableReason
import app.ridemate.net.ApiClient
import app.ridemate.net.ApiResult
import app.ridemate.net.ParseNotificationRequest
import app.ridemate.net.ParseNotificationResponse
import app.ridemate.testing.FakeApiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/v1/parse-notification` with canned results; records what was sent. */
private class ParseApi(private val base: ApiClient = FakeApiClient()) : ApiClient by base {
    val sent = mutableListOf<Pair<ParseNotificationRequest, String?>>()
    var result: ApiResult<ParseNotificationResponse> = ApiResult.Ok(ParseNotificationResponse("assigned", "captain is on the way", "r1"))

    override suspend fun parseNotification(request: ParseNotificationRequest, sessionId: String?): ApiResult<ParseNotificationResponse> {
        sent += request to sessionId
        return result
    }
}

class ApiNotificationClassifierTest {
    private val api = ParseApi()
    private var auth: AuthState = AuthState.Google("rider@example.com", "Rider", null, "uid")
    private var aiOn = true
    private var session: String? = "s1"
    private val classifier = ApiNotificationClassifier(api, { auth }, { aiOn }, { session })

    private fun request(app: String = "rapido", title: String = "Rapido", text: String = "Captain Ravi Kumar will reach soon, call 9876543210") =
        ClassifyRequest(app, 4_242L, title, text, "ride_status", "status", true, RideState.SEARCHING)

    @Test
    fun `a Google user in a session gets the server's label, with PII masked again before upload`() = runBlocking {
        val result = classifier.classify(request())

        assertEquals(ClassifierResult.Labelled(ClassifierLabel.ASSIGNED), result)
        val (sent, sessionId) = api.sent.single()
        assertEquals("s1", sessionId)
        assertEquals(4_242L, sent.versionCode)
        assertEquals(RideState.SEARCHING, sent.legState)
        assertFalse(sent.textMasked!!.contains("9876543210"))
        assertFalse(sent.textMasked!!.contains("Ravi"))
        assertTrue(sent.textMasked!!.contains("<m>"))
    }

    @Test
    fun `anonymous, signed-out users and the kill switch never reach the network`() = runBlocking {
        auth = AuthState.Anonymous("anon")
        assertEquals(ClassifierResult.Unavailable(UnavailableReason.ANONYMOUS), classifier.classify(request()))
        auth = AuthState.SignedOut
        assertEquals(ClassifierResult.Unavailable(UnavailableReason.SIGNED_OUT), classifier.classify(request()))
        auth = AuthState.Google(null, null, null, "uid")
        aiOn = false
        assertEquals(ClassifierResult.Unavailable(UnavailableReason.KILL_SWITCH), classifier.classify(request()))
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun `nothing is sent outside a session or for demo providers`() = runBlocking {
        assertTrue(classifier.classify(request(app = "demo_pepper")) is ClassifierResult.Unavailable)
        session = null
        assertTrue(classifier.classify(request()) is ClassifierResult.Unavailable)
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun `server refusals map to the fallback reasons, timeouts and errors fail`() = runBlocking {
        api.result = ApiResult.AiPaused("quota_exhausted", "13:30", null)
        assertEquals(ClassifierResult.Unavailable(UnavailableReason.QUOTA), classifier.classify(request()))
        api.result = ApiResult.AiPaused("ai_unavailable", null, null)
        assertEquals(ClassifierResult.Unavailable(UnavailableReason.BREAKER_OPEN), classifier.classify(request()))
        api.result = ApiResult.AiPaused("ai_paused", "13:30", "budget")
        assertEquals(ClassifierResult.Unavailable(UnavailableReason.AI_PAUSED), classifier.classify(request()))
        api.result = ApiResult.Offline
        assertEquals(ClassifierResult.Unavailable(UnavailableReason.OFFLINE), classifier.classify(request()))
        api.result = ApiResult.GoogleRequired
        assertEquals(ClassifierResult.Unavailable(UnavailableReason.ANONYMOUS), classifier.classify(request()))
        api.result = ApiResult.Ok(ParseNotificationResponse("something-new", null, null))
        assertEquals(ClassifierResult.Labelled(ClassifierLabel.UNKNOWN), classifier.classify(request()))

        api.result = ApiResult.Timeout
        assertTrue(runCatching { classifier.classify(request()) }.exceptionOrNull() is ClassifierCallFailed)
        api.result = ApiResult.Http(500)
        assertTrue(runCatching { classifier.classify(request()) }.exceptionOrNull() is ClassifierCallFailed)
    }

    @Test
    fun `the masker gives core mask spans`() {
        val text = "Captain Ravi is on the way. OTP 4719"
        val spans = PiiTextMasker().spans(text)
        val masked = spans.map { text.substring(it) }
        assertTrue(masked.toString(), "Ravi" in masked)
        assertTrue(masked.toString(), "4719" in masked)
    }
}
