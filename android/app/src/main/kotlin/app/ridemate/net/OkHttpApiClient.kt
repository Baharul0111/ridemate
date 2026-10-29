package app.ridemate.net

import app.ridemate.auth.IdTokenResult
import app.ridemate.auth.IdTokenSource
import app.ridemate.core.model.LatLng
import app.ridemate.util.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * [ApiClient] over OkHttp. The [http] client should have no read/write timeouts of its own (each call gets an
 * OkHttp call timeout equal to its remaining budget) and a short connect timeout; see [defaultHttpClient].
 *
 * @param baseUrl Remote Config `api_base_url`, read on every call (so a config update applies immediately).
 * @param tokens Firebase ID tokens (forced refresh once after a 401).
 * @param appVersionCode sent as `X-App-Version`.
 * @param clock elapsed-realtime base for every deadline.
 * @param random jitter source for the single retry.
 * @param newRequestId one id per logical call (tests pin it).
 * @param hostPolicy the hosts [baseUrl] may name ([ApiHostPolicy.PRODUCTION]); any other host is "not configured".
 */
class OkHttpApiClient(
    private val http: OkHttpClient,
    private val baseUrl: () -> String?,
    private val tokens: IdTokenSource,
    private val appVersionCode: Int,
    private val clock: Clock,
    private val timing: ApiTiming = ApiTiming.PRODUCTION,
    private val random: Random = Random.Default,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
    private val listener: ApiCallListener? = null,
    private val hostPolicy: ApiHostPolicy = ApiHostPolicy.PRODUCTION,
) : ApiClient {

    // -----------------------------------------------------------------------------------------------------------
    // Endpoints
    // -----------------------------------------------------------------------------------------------------------

    override suspend fun readScreen(request: ReadScreenRequest, budget: TapBudget): ApiResult<ReadScreenResponse> {
        val endpoint = Endpoint.READ_SCREEN
        val media = checkImage(request.image) ?: return finishLocal(endpoint, BAD_MEDIA)
        if (request.image.size > ApiLimits.MAX_IMAGE_BYTES) return finishLocal(endpoint, TOO_LARGE)
        val meta = ApiJson.encodeToString(
            ReadScreenMetaWire.serializer(),
            ReadScreenMetaWire(request.provider.wireId, request.frameWidth, request.frameHeight, request.demo),
        )
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("meta", meta)
            .addFormDataPart("image", "frame.${media.extension}", request.image.toRequestBody(media.type))
            .build()
        return execute(endpoint, body, request.sessionId, budget.deadlineElapsedMs) { text ->
            val obj = parseObject(text)
            ReadScreenResponse(
                read = obj["read"] as? JsonObject ?: throw IllegalArgumentException("read"),
                source = obj.string("source") ?: "live",
                model = obj.string("model"),
                latencyMs = obj.long("latency_ms"),
                requestId = obj.string("request_id"),
            )
        }
    }

    override suspend fun reread(request: RereadRequest, budget: TapBudget): ApiResult<RereadResponse> {
        val endpoint = Endpoint.REREAD
        if (request.crops.isEmpty()) return finishLocal(endpoint, ApiResult.Http(422, "invalid"))
        val medias = request.crops.map { checkImage(it.image) ?: return finishLocal(endpoint, BAD_MEDIA) }
        if (request.crops.sumOf { it.image.size } > ApiLimits.MAX_IMAGE_BYTES) return finishLocal(endpoint, TOO_LARGE)
        val meta = ApiJson.encodeToString(
            RereadMetaWire.serializer(),
            RereadMetaWire(request.provider.wireId, request.crops.map { RereadRowWire(it.rowId) }),
        )
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("meta", meta)
        request.crops.forEachIndexed { i, crop ->
            val media = medias[i]
            builder.addFormDataPart("crop_${crop.rowId}", "crop_${crop.rowId}.${media.extension}", crop.image.toRequestBody(media.type))
        }
        return execute(endpoint, builder.build(), request.sessionId, budget.deadlineElapsedMs) { text ->
            val obj = parseObject(text)
            val rows = (obj["rows"] as? JsonArray ?: throw IllegalArgumentException("rows")).map { it.jsonObject }
            RereadResponse(rows, obj.string("source") ?: "live", obj.string("request_id"))
        }
    }

    override suspend fun point(request: PointRequest): ApiResult<PointResponse> {
        val endpoint = Endpoint.POINT
        val media = checkImage(request.image) ?: return finishLocal(endpoint, BAD_MEDIA)
        if (request.image.size > ApiLimits.MAX_IMAGE_BYTES) return finishLocal(endpoint, TOO_LARGE)
        val meta = ApiJson.encodeToString(PointMetaWire.serializer(), PointMetaWire(request.provider.wireId, request.target))
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("meta", meta)
            .addFormDataPart("image", "frame.${media.extension}", request.image.toRequestBody(media.type))
            .build()
        return execute(endpoint, body, request.sessionId, deadlineFor(endpoint)) { text ->
            val obj = parseObject(text)
            val box = (obj["box_0_1000"] as? JsonArray)?.map { (it as JsonPrimitive).intOrNull ?: throw IllegalArgumentException("box") }
            require(box == null || box.size == 4) { "box" }
            PointResponse(supported = obj.bool("supported") ?: false, box0to1000 = box)
        }
    }

    override suspend fun parseNotification(
        request: ParseNotificationRequest,
        sessionId: String?,
    ): ApiResult<ParseNotificationResponse> {
        val wire = ParseNotificationWire(
            app = request.provider.wireId,
            versionCode = request.versionCode,
            title = request.titleMasked,
            text = request.textMasked,
            channelId = request.channelId,
            category = request.category,
            isOngoing = request.isOngoing,
            legState = request.legState.name,
        )
        return postJson(Endpoint.PARSE_NOTIFICATION, ParseNotificationWire.serializer(), wire, sessionId) { text ->
            val obj = parseObject(text)
            ParseNotificationResponse(
                label = obj.string("label") ?: throw IllegalArgumentException("label"),
                evidenceSpan = obj.string("evidence_span"),
                requestId = obj.string("request_id"),
            )
        }
    }

    override suspend fun placePick(request: PlacePickRequest, sessionId: String?): ApiResult<PlacePickResponse> {
        val wire = PlacePickWire(request.transcript, request.lang, request.candidates.map { PlaceCandidateWire(it.id, it.label) })
        return postJson(Endpoint.PLACE_PICK, PlacePickWire.serializer(), wire, sessionId) { text ->
            val pick = parseObject(text).string("pick") ?: throw IllegalArgumentException("pick")
            PlacePickResponse(if (pick == "none") null else pick)
        }
    }

    override suspend fun event(report: SessionEventReport): ApiResult<Unit> =
        postJson(Endpoint.EVENT, SessionEventReport.serializer(), report, report.sessionId) { }

    override suspend fun erase(): ApiResult<EraseResponse> =
        execute(Endpoint.ERASE, EMPTY_JSON_BODY, null, deadlineFor(Endpoint.ERASE)) { text ->
            if (text.isBlank()) EraseResponse(erased = true) else ApiJson.decodeFromString(EraseResponse.serializer(), text)
        }

    override suspend fun configCheck(): ApiResult<ConfigCheckResponse> =
        execute(Endpoint.CONFIG_CHECK, null, null, deadlineFor(Endpoint.CONFIG_CHECK)) { text ->
            ApiJson.decodeFromString(ConfigCheckResponse.serializer(), text)
        }

    override suspend fun registerOwnerFcmToken(token: String): ApiResult<Unit> =
        postJson(Endpoint.OWNER_FCM_TOKEN, OwnerTokenWire.serializer(), OwnerTokenWire(token), null) { }

    override suspend fun ondcSearch(sessionId: String, pickup: LatLng, drop: LatLng): ApiResult<OndcSearchResponse> =
        postJson(
            Endpoint.ONDC_SEARCH,
            OndcSearchWire.serializer(),
            OndcSearchWire(sessionId, LatLngWire.of(pickup), LatLngWire.of(drop)),
            sessionId,
        ) { text -> OndcSearchResponse(parseObject(text).string("txn_id") ?: throw IllegalArgumentException("txn_id")) }

    override suspend fun ondcSelect(txnId: String, itemId: String): ApiResult<Unit> =
        ondc(Endpoint.ONDC_SELECT, OndcTxnWire(txnId, itemId = itemId))

    override suspend fun ondcInit(txnId: String): ApiResult<Unit> = ondc(Endpoint.ONDC_INIT, OndcTxnWire(txnId))

    override suspend fun ondcConfirm(txnId: String, tapId: String): ApiResult<Unit> =
        ondc(Endpoint.ONDC_CONFIRM, OndcTxnWire(txnId, tapId = tapId))

    override suspend fun ondcStatus(txnId: String): ApiResult<Unit> = ondc(Endpoint.ONDC_STATUS, OndcTxnWire(txnId))

    override suspend fun ondcCancel(txnId: String, tapId: String, confirm: Boolean): ApiResult<Unit> =
        ondc(Endpoint.ONDC_CANCEL, OndcTxnWire(txnId, tapId = tapId, confirm = confirm))

    override suspend fun ondcKeepRide(txnId: String, tapId: String): ApiResult<Unit> =
        ondc(Endpoint.ONDC_CANCEL, OndcTxnWire(txnId, tapId = tapId, keep = true))

    override suspend fun ondcAbandon(txnId: String): ApiResult<Unit> =
        ondc(Endpoint.ONDC_CANCEL, OndcTxnWire(txnId, abandon = true))

    override suspend fun healthz(): ApiResult<Boolean> =
        execute(Endpoint.HEALTHZ, null, null, deadlineFor(Endpoint.HEALTHZ)) { text -> parseObject(text).bool("ok") ?: false }

    override suspend fun readyz(): ApiResult<JsonObject> =
        execute(Endpoint.READYZ, null, null, deadlineFor(Endpoint.READYZ)) { text -> parseObject(text) }

    private suspend fun ondc(endpoint: Endpoint, wire: OndcTxnWire): ApiResult<Unit> =
        postJson(endpoint, OndcTxnWire.serializer(), wire, null) { }

    // -----------------------------------------------------------------------------------------------------------
    // Core
    // -----------------------------------------------------------------------------------------------------------

    private suspend fun <W, T> postJson(
        endpoint: Endpoint,
        serializer: KSerializer<W>,
        wire: W,
        sessionId: String?,
        decode: (String) -> T,
    ): ApiResult<T> {
        val bytes = ApiJson.encodeToString(serializer, wire).encodeToByteArray()
        if (bytes.size > ApiLimits.MAX_JSON_BYTES) return finishLocal(endpoint, TOO_LARGE)
        return execute(endpoint, bytes.toRequestBody(JSON), sessionId, deadlineFor(endpoint), decode)
    }

    private fun deadlineFor(endpoint: Endpoint): Long = clock.elapsedRealtimeMs() + timing.phoneDeadlineMs(endpoint)

    private fun <T> finishLocal(endpoint: Endpoint, result: ApiResult<T>): ApiResult<T> {
        listener?.onCallFinished(endpoint, outcomeName(result), 0, 0)
        return result
    }

    private suspend fun <T> execute(
        endpoint: Endpoint,
        body: RequestBody?,
        sessionId: String?,
        deadlineElapsedMs: Long,
        decode: (String) -> T,
    ): ApiResult<T> {
        val started = clock.elapsedRealtimeMs()
        val attempts = intArrayOf(0)
        val result = executeAttempts(endpoint, body, sessionId, deadlineElapsedMs, attempts, decode)
        listener?.onCallFinished(endpoint, outcomeName(result), attempts[0], clock.elapsedRealtimeMs() - started)
        return result
    }

    private suspend fun <T> executeAttempts(
        endpoint: Endpoint,
        body: RequestBody?,
        sessionId: String?,
        deadlineElapsedMs: Long,
        attempts: IntArray,
        decode: (String) -> T,
    ): ApiResult<T> {
        val url = resolveUrl(endpoint) ?: return ApiResult.NotConfigured
        val requestId = newRequestId()
        var token: String? = null
        if (endpoint.auth == AuthMode.REQUIRED) {
            when (val t = idTokenWithin(false, deadlineElapsedMs)) {
                is IdTokenResult.Token -> token = t.value
                IdTokenResult.NetworkError -> return ApiResult.Offline
                IdTokenResult.SignedOut, IdTokenResult.Failed -> return ApiResult.AuthNeeded
                null -> return ApiResult.Timeout
            }
        }
        var transientRetryUsed = false
        var authRefreshUsed = false
        val serverDeadlineMs = timing.serverDeadlineMs(endpoint)

        // Retry rule (§4.12): once, after 1–2 s of jitter, and only if what is left after the jitter still exceeds
        // the server's own deadline (otherwise the server could not finish anyway).
        suspend fun mayRetry(): Boolean {
            if (transientRetryUsed) return false
            val jitter = if (timing.jitterMaxMs == timing.jitterMinMs) timing.jitterMinMs
            else random.nextLong(timing.jitterMinMs, timing.jitterMaxMs + 1)
            val remainingAfterJitter = deadlineElapsedMs - clock.elapsedRealtimeMs() - jitter
            if (remainingAfterJitter <= serverDeadlineMs) return false
            transientRetryUsed = true
            delay(jitter)
            return true
        }

        while (true) {
            val remaining = deadlineElapsedMs - clock.elapsedRealtimeMs()
            if (remaining <= 0) return ApiResult.Timeout
            attempts[0]++
            val request = buildRequest(url, endpoint, body, token, requestId, sessionId, remaining)
            val call = http.newCall(request)
            call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
            val raw = try {
                call.awaitRaw()
            } catch (e: IOException) {
                val failure = classify(e, deadlineElapsedMs)
                if (failure == ApiResult.Timeout && deadlineElapsedMs - clock.elapsedRealtimeMs() <= 0) return failure
                if (mayRetry()) continue
                return failure
            }

            if (raw.code in 200..299) {
                return try {
                    ApiResult.Ok(decode(raw.body))
                } catch (e: Exception) {
                    ApiResult.Http(raw.code, MALFORMED)
                }
            }
            if (raw.code == 401) {
                if (endpoint.auth != AuthMode.REQUIRED || authRefreshUsed) return ApiResult.AuthNeeded
                authRefreshUsed = true
                when (val t = idTokenWithin(true, deadlineElapsedMs)) {
                    is IdTokenResult.Token -> {
                        token = t.value
                        continue
                    }
                    IdTokenResult.NetworkError -> return ApiResult.Offline
                    IdTokenResult.SignedOut, IdTokenResult.Failed -> return ApiResult.AuthNeeded
                    null -> return ApiResult.Timeout
                }
            }
            val mapped = mapHttpError(raw.code, raw.body, raw.retryAfter)
            if (mapped.retryable && mayRetry()) continue
            return mapped.result
        }
    }

    /**
     * The ID token, fetched within the call's deadline: a Firebase refresh that hangs (flaky network) must not
     * stretch the call past its budget ("it never hangs"). Null when the deadline passed first.
     */
    private suspend fun idTokenWithin(forceRefresh: Boolean, deadlineElapsedMs: Long): IdTokenResult? {
        val remaining = deadlineElapsedMs - clock.elapsedRealtimeMs()
        if (remaining <= 0) return null
        return withTimeoutOrNull(remaining) { tokens.idToken(forceRefresh) }
    }

    /**
     * The endpoint's URL under `api_base_url`, or null (NotConfigured: no request, manual mode) when it is empty, not a
     * URL, or names a host outside [hostPolicy] (§4.11: the ID token and the user's data go to RideMate's server only).
     */
    private fun resolveUrl(endpoint: Endpoint): HttpUrl? {
        val base = baseUrl()?.trim().orEmpty()
        if (base.isEmpty()) return null
        val parsed = base.toHttpUrlOrNull() ?: return null
        if (!hostPolicy.allows(parsed)) return null
        return parsed.newBuilder().addPathSegments(endpoint.path).build()
    }

    private fun buildRequest(
        url: HttpUrl,
        endpoint: Endpoint,
        body: RequestBody?,
        token: String?,
        requestId: String,
        sessionId: String?,
        remainingMs: Long,
    ): Request {
        val builder = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header(HEADER_REQUEST_ID, requestId)
            .header(HEADER_APP_VERSION, appVersionCode.toString())
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (sessionId != null) builder.header(HEADER_SESSION_ID, sessionId)
        if (endpoint.usesTapBudget) builder.header(HEADER_TAP_DEADLINE, remainingMs.toString())
        when (endpoint.method) {
            "GET" -> builder.get()
            else -> builder.method(endpoint.method, body ?: EMPTY_JSON_BODY)
        }
        return builder.build()
    }

    private fun classify(e: IOException, deadlineElapsedMs: Long): ApiResult<Nothing> = when {
        deadlineElapsedMs - clock.elapsedRealtimeMs() <= 0 -> ApiResult.Timeout
        // OkHttp's call timeout surfaces as a plain InterruptedIOException("timeout").
        e is InterruptedIOException && e !is SocketTimeoutException -> ApiResult.Timeout
        else -> ApiResult.Offline
    }

    private class RawResponse(val code: Int, val body: String, val retryAfter: String?)

    /** Enqueues the call, reads the whole body on OkHttp's thread, and cancels the call if the coroutine is. */
    private suspend fun Call.awaitRaw(): RawResponse = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val raw = try {
                    response.use { RawResponse(it.code, it.body?.string().orEmpty(), it.header("Retry-After")) }
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                    return
                }
                cont.resume(raw)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    private class CheckedMedia(val type: MediaType, val extension: String)

    private fun checkImage(bytes: ByteArray): CheckedMedia? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
            CheckedMedia(JPEG, "jpg")
        bytes.size >= 8 && PNG_MAGIC.indices.all { bytes[it] == PNG_MAGIC[it] } -> CheckedMedia(PNG, "png")
        else -> null
    }

    companion object {
        const val HEADER_REQUEST_ID = "X-Request-Id"
        const val HEADER_SESSION_ID = "X-Session-Id"
        const val HEADER_APP_VERSION = "X-App-Version"
        const val HEADER_TAP_DEADLINE = "X-Tap-Deadline-Ms"
        const val MALFORMED = "malformed"

        private val JSON: MediaType = "application/json; charset=utf-8".toMediaType()
        private val JPEG: MediaType = "image/jpeg".toMediaType()
        private val PNG: MediaType = "image/png".toMediaType()
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val EMPTY_JSON_BODY: RequestBody = "{}".toRequestBody(JSON)
        private val TOO_LARGE = ApiResult.Http(413, "too_large")
        private val BAD_MEDIA = ApiResult.Http(415, "bad_media")

        /**
         * The production client: 5 s connect timeout, no read/write timeouts (each call carries a call timeout
         * equal to its remaining budget), no OkHttp-level retries (the single retry rule is ours), no logging.
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .build()

        internal fun outcomeName(result: ApiResult<*>): String = when (result) {
            is ApiResult.Http -> "Http(${result.code})"
            is ApiResult.AiPaused -> "AiPaused(${result.code})"
            else -> result::class.simpleName ?: "Unknown"
        }
    }
}

/** A mapped non-2xx response and whether the single retry may be spent on it. */
internal class MappedError(val result: ApiResult<Nothing>, val retryable: Boolean)

/**
 * Maps a non-2xx, non-401 response with the §1.2 error envelope to an [ApiResult]. Unknown or missing codes fall
 * back on the status; transient statuses without a definitive code (500/502/503/504) are retryable, and so is
 * `model_timeout` (goal 21: "if the AI is slow … it tries once more"). Whether the retry is actually made is
 * still decided by the budget rule in [OkHttpApiClient]: only if what is left exceeds the server deadline.
 */
internal fun mapHttpError(status: Int, body: String, retryAfterHeader: String?): MappedError {
    val error = runCatching { ApiJson.decodeFromString(ErrorEnvelopeWire.serializer(), body).error }.getOrNull()
    val code = error?.code
    val retryAfter = error?.retryAfterS ?: retryAfterHeader?.trim()?.toLongOrNull()
    return when {
        status == 401 -> MappedError(ApiResult.AuthNeeded, false)
        code == "google_required" -> MappedError(ApiResult.GoogleRequired, false)
        code == "rate_limited" -> MappedError(ApiResult.RateLimited(retryAfter), false)
        code != null && code in ApiResult.AI_PAUSE_CODES ->
            MappedError(ApiResult.AiPaused(code, error?.resumeAtIst, error?.reason), false)
        code == "model_timeout" -> MappedError(ApiResult.Timeout, true)
        status == 429 -> MappedError(ApiResult.RateLimited(retryAfter), false)
        status == 504 -> MappedError(ApiResult.Timeout, code == null)
        status == 500 || status == 502 || status == 503 -> MappedError(ApiResult.Http(status, code), code == null)
        else -> MappedError(ApiResult.Http(status, code), false)
    }
}

private fun parseObject(text: String): JsonObject = ApiJson.parseToJsonElement(text).jsonObject

private fun JsonObject.prim(key: String): JsonPrimitive? {
    val element: JsonElement = this[key] ?: return null
    return if (element is JsonNull) null else element as? JsonPrimitive
}

private fun JsonObject.string(key: String): String? = prim(key)?.contentOrNull

private fun JsonObject.long(key: String): Long? = prim(key)?.longOrNull

private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull
