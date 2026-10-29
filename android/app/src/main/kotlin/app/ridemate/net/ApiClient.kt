package app.ridemate.net

import app.ridemate.core.model.LatLng
import kotlinx.serialization.json.JsonObject

/**
 * RideMate's server API (contracts §1.3–1.4). Every call:
 * - returns [ApiResult.NotConfigured] without a request when Remote Config has no `api_base_url`;
 * - sends `Authorization`, `X-Request-Id` (one UUID per logical call, reused on its retry so the server counts it
 *   once), `X-App-Version`, and `X-Session-Id` where a session id is given;
 * - finishes within its phone deadline (server deadline + 5 s; read-screen and reread within the tap's
 *   [TapBudget]) and retries at most once, after a 1–2 s jitter, only when the remaining budget still exceeds the
 *   server deadline;
 * - never logs bodies or tokens.
 *
 * All functions are main-safe suspend functions and never throw for network or server failures.
 */
interface ApiClient {
    /** `POST /v1/read-screen` (multipart `meta` + `image`). Sends `X-Tap-Deadline-Ms`. */
    suspend fun readScreen(request: ReadScreenRequest, budget: TapBudget): ApiResult<ReadScreenResponse>

    /** `POST /v1/reread` (multipart `meta` + `crop_<row_id>` files), within the same tap [budget]. */
    suspend fun reread(request: RereadRequest, budget: TapBudget): ApiResult<RereadResponse>

    /** `POST /v1/point` (multipart `meta` + `image`). */
    suspend fun point(request: PointRequest): ApiResult<PointResponse>

    /** `POST /v1/parse-notification`. */
    suspend fun parseNotification(request: ParseNotificationRequest, sessionId: String?): ApiResult<ParseNotificationResponse>

    /** `POST /v1/place-pick`. */
    suspend fun placePick(request: PlacePickRequest, sessionId: String?): ApiResult<PlacePickResponse>

    /** `POST /v1/event` (202). */
    suspend fun event(report: SessionEventReport): ApiResult<Unit>

    /** `POST /v1/erase`: deletes the caller's server data. */
    suspend fun erase(): ApiResult<EraseResponse>

    /** `GET /v1/config-check`. */
    suspend fun configCheck(): ApiResult<ConfigCheckResponse>

    /** `POST /v1/owner/fcm-token` (owner accounts only; 204). */
    suspend fun registerOwnerFcmToken(token: String): ApiResult<Unit>

    /** `POST /v1/ondc/search` → the transaction id; quotes then arrive in Firestore `ondc_txn/{txnId}`. */
    suspend fun ondcSearch(sessionId: String, pickup: LatLng, drop: LatLng): ApiResult<OndcSearchResponse>

    suspend fun ondcSelect(txnId: String, itemId: String): ApiResult<Unit>

    suspend fun ondcInit(txnId: String): ApiResult<Unit>

    /** [tapId] links the confirm to the user's tap (I1). */
    suspend fun ondcConfirm(txnId: String, tapId: String): ApiResult<Unit>

    suspend fun ondcStatus(txnId: String): ApiResult<Unit>

    /** [confirm] = false → soft cancel (fee lands in Firestore); true → confirm the cancel. */
    suspend fun ondcCancel(txnId: String, tapId: String, confirm: Boolean): ApiResult<Unit>

    /**
     * `POST /v1/ondc/cancel {keep: true}`: the user kept the ride after seeing the fee ([tapId], I1). The server returns
     * the transaction to its ride state, so progress shows again, and ignores a quote that still arrives for it.
     */
    suspend fun ondcKeepRide(txnId: String, tapId: String): ApiResult<Unit> = ApiResult.NotConfigured

    /**
     * `POST /v1/ondc/cancel {abandon: true}`: the phone no longer shows a transaction that has no order yet, so the
     * server clears its pickup and drop now. A booked ride is never touched (the server ignores it).
     */
    suspend fun ondcAbandon(txnId: String): ApiResult<Unit> = ApiResult.NotConfigured

    /** `GET /healthz` (no auth). */
    suspend fun healthz(): ApiResult<Boolean>

    /** `GET /readyz` (no auth): the raw `checks` report. */
    suspend fun readyz(): ApiResult<JsonObject>
}

/** Privacy-safe call metrics (endpoint, outcome, attempts, latency; never bodies, ids or tokens). */
fun interface ApiCallListener {
    fun onCallFinished(endpoint: Endpoint, outcome: String, attempts: Int, latencyMs: Long)
}
