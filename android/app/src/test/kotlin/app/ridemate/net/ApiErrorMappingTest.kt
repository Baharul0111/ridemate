package app.ridemate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorMappingTest {

    private fun env(code: String, extra: String = "") = """{"error":{"code":"$code"$extra}}"""

    @Test
    fun `definitive codes are not retryable`() {
        val definitive = listOf(
            403 to env("google_required"),
            429 to env("rate_limited"),
            429 to env("quota_exhausted"),
            503 to env("ai_unavailable"),
            503 to env("ondc_unavailable"),
            422 to env("invalid"),
        )
        definitive.forEach { (status, body) -> assertFalse("$status $body", mapHttpError(status, body, null).retryable) }
    }

    @Test
    fun `a model timeout is worth one more try`() {
        val mapped = mapHttpError(504, env("model_timeout"), null)
        assertEquals(ApiResult.Timeout, mapped.result)
        assertTrue(mapped.retryable)
    }

    @Test
    fun `bare gateway failures are retryable`() {
        listOf(500, 502, 503, 504).forEach { assertTrue("$it", mapHttpError(it, "", null).retryable) }
        assertEquals(ApiResult.Timeout, mapHttpError(504, "<html>gateway</html>", null).result)
        assertEquals(ApiResult.Http(502, null), mapHttpError(502, "", null).result)
    }

    @Test
    fun `retry-after comes from the envelope or the header`() {
        assertEquals(ApiResult.RateLimited(12), mapHttpError(429, env("rate_limited", ""","retry_after_s":12"""), "99").result)
        assertEquals(ApiResult.RateLimited(99), mapHttpError(429, env("rate_limited"), "99").result)
        assertEquals(ApiResult.RateLimited(null), mapHttpError(429, "", null).result)
    }

    @Test
    fun `unknown codes fall back on the status`() {
        assertEquals(ApiResult.Http(418, "teapot"), mapHttpError(418, env("teapot"), null).result)
        assertEquals(ApiResult.Http(400, null), mapHttpError(400, "garbage", null).result)
        assertEquals(ApiResult.AuthNeeded, mapHttpError(401, env("auth_invalid"), null).result)
    }

    @Test
    fun `result helpers`() {
        val ok: ApiResult<Int> = ApiResult.Ok(2)
        assertEquals(ApiResult.Ok(4), ok.map { it * 2 })
        assertEquals(2, ok.getOrNull())
        val failed: ApiResult<Int> = ApiResult.Offline
        assertEquals(ApiResult.Offline, failed.map { it * 2 })
        assertEquals(null, failed.getOrNull())
        assertTrue(ok.isOk)
        assertFalse(failed.isOk)
        assertEquals("Http(503)", OkHttpApiClient.outcomeName(ApiResult.Http(503)))
        assertEquals("AiPaused(quota_exhausted)", OkHttpApiClient.outcomeName(ApiResult.AiPaused("quota_exhausted", null, null)))
        assertEquals("Offline", OkHttpApiClient.outcomeName(ApiResult.Offline))
    }
}
