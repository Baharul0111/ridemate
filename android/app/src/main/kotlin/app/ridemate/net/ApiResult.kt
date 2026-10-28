package app.ridemate.net

/**
 * Outcome of one API call (contracts §1.2). Every non-[Ok] result on an AI endpoint means the same thing on the
 * phone: manual mode (type the price) while the guardian keeps working; [AiPaused.resumeAtIst] feeds
 * "AI reading resumes at hh:mm (IST)".
 */
sealed interface ApiResult<out T> {

    /** 2xx with a decoded body. */
    data class Ok<out T>(val body: T) : ApiResult<T>

    /**
     * AI is paused or unavailable for this user: `ai_paused`, `quota_exhausted` (429), `ai_unavailable`,
     * `meter_unavailable` (503) or `blocked` (403). [resumeAtIst] is "HH:mm" when the server knows it.
     */
    data class AiPaused(val code: String, val resumeAtIst: String?, val reason: String?) : ApiResult<Nothing>

    /** No usable ID token (signed out, or 401 again after one forced refresh). */
    data object AuthNeeded : ApiResult<Nothing>

    /** 403 `google_required`: the endpoint needs a Google (not anonymous) sign-in. */
    data object GoogleRequired : ApiResult<Nothing>

    /** 429 `rate_limited` (per-IP bucket). */
    data class RateLimited(val retryAfterS: Long?) : ApiResult<Nothing>

    /** No connection to the server (DNS, connect, reset). */
    data object Offline : ApiResult<Nothing>

    /** The phone deadline passed, or the server reported `model_timeout` / a gateway timeout. */
    data object Timeout : ApiResult<Nothing>

    /** Remote Config has no `api_base_url` (or it is not a valid URL): no request was made. */
    data object NotConfigured : ApiResult<Nothing>

    /**
     * Any other HTTP status (404, 413, 415, 422, 403 `forbidden`, 5xx without a known code, …) or an undecodable
     * 2xx body ([errorCode] = `malformed`). Local pre-checks use 413 `too_large` and 415 `bad_media`.
     */
    data class Http(val code: Int, val errorCode: String? = null) : ApiResult<Nothing>

    val isOk: Boolean get() = this is Ok

    fun getOrNull(): T? = (this as? Ok)?.body

    /** Maps the body of [Ok]; every failure passes through unchanged. */
    fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Ok -> Ok(transform(body))
        is AiPaused -> this
        AuthNeeded -> AuthNeeded
        GoogleRequired -> GoogleRequired
        is RateLimited -> this
        Offline -> Offline
        Timeout -> Timeout
        NotConfigured -> NotConfigured
        is Http -> this
    }

    companion object {
        /** Error codes that mean "AI paused for you" (manual mode, maybe with a resume time). */
        val AI_PAUSE_CODES: Set<String> = setOf("ai_paused", "quota_exhausted", "ai_unavailable", "meter_unavailable", "blocked")
    }
}
