package app.ridemate.testing

import app.ridemate.core.model.LatLng
import app.ridemate.net.ApiClient
import app.ridemate.net.ApiResult
import app.ridemate.net.ConfigCheckResponse
import app.ridemate.net.EraseResponse
import app.ridemate.net.OndcSearchResponse
import app.ridemate.net.ParseNotificationRequest
import app.ridemate.net.ParseNotificationResponse
import app.ridemate.net.PlacePickRequest
import app.ridemate.net.PlacePickResponse
import app.ridemate.net.PointRequest
import app.ridemate.net.PointResponse
import app.ridemate.net.ReadScreenRequest
import app.ridemate.net.ReadScreenResponse
import app.ridemate.net.RereadRequest
import app.ridemate.net.RereadResponse
import app.ridemate.net.SessionEventReport
import app.ridemate.net.TapBudget
import kotlinx.serialization.json.JsonObject

/**
 * An [ApiClient] whose answers each test programs (no network). Every call is recorded in [calls] as
 * `endpoint[:detail]`; the read-screen, point, ONDC and owner-token requests are kept for assertions.
 */
class ProgrammableApiClient : ApiClient {
    val calls = mutableListOf<String>()

    val readRequests = mutableListOf<ReadScreenRequest>()
    var readScreen: suspend (ReadScreenRequest) -> ApiResult<ReadScreenResponse> = { ApiResult.NotConfigured }

    val pointRequests = mutableListOf<PointRequest>()
    var point: suspend (PointRequest) -> ApiResult<PointResponse> = { ApiResult.NotConfigured }

    var ondcSearch: suspend () -> ApiResult<OndcSearchResponse> = { ApiResult.NotConfigured }

    /** Answer of select / init / confirm / status / cancel, by endpoint name (`select`, `init`, …). */
    var ondcStep: suspend (String) -> ApiResult<Unit> = { ApiResult.Ok(Unit) }

    /** Runs after an ONDC step answered Ok (tests use it to make the "seller" write Firestore). */
    var afterOndcStep: (String) -> Unit = {}

    val ownerTokens = mutableListOf<String>()
    var ownerToken: ApiResult<Unit> = ApiResult.Ok(Unit)

    override suspend fun readScreen(request: ReadScreenRequest, budget: TapBudget): ApiResult<ReadScreenResponse> {
        calls += "read-screen:${request.provider.wireId}"
        readRequests += request
        return readScreen.invoke(request)
    }

    override suspend fun point(request: PointRequest): ApiResult<PointResponse> {
        calls += "point:${request.target}"
        pointRequests += request
        return point.invoke(request)
    }

    override suspend fun ondcSearch(sessionId: String, pickup: LatLng, drop: LatLng): ApiResult<OndcSearchResponse> {
        calls += "ondc.search"
        return ondcSearch.invoke()
    }

    override suspend fun ondcSelect(txnId: String, itemId: String): ApiResult<Unit> = step("select", "$txnId:$itemId")
    override suspend fun ondcInit(txnId: String): ApiResult<Unit> = step("init", txnId)
    override suspend fun ondcConfirm(txnId: String, tapId: String): ApiResult<Unit> = step("confirm", "$txnId:$tapId")
    override suspend fun ondcStatus(txnId: String): ApiResult<Unit> = step("status", txnId)
    override suspend fun ondcCancel(txnId: String, tapId: String, confirm: Boolean): ApiResult<Unit> =
        step(if (confirm) "cancel.confirm" else "cancel.soft", "$txnId:$tapId")
    override suspend fun ondcKeepRide(txnId: String, tapId: String): ApiResult<Unit> = step("keep", "$txnId:$tapId")
    override suspend fun ondcAbandon(txnId: String): ApiResult<Unit> = step("abandon", txnId)

    private suspend fun step(name: String, detail: String): ApiResult<Unit> {
        calls += "ondc.$name:$detail"
        val result = ondcStep.invoke(name)
        if (result is ApiResult.Ok) afterOndcStep(name)
        return result
    }

    override suspend fun registerOwnerFcmToken(token: String): ApiResult<Unit> {
        calls += "owner.fcm-token"
        ownerTokens += token
        return ownerToken
    }

    override suspend fun reread(request: RereadRequest, budget: TapBudget): ApiResult<RereadResponse> {
        calls += "reread"
        return ApiResult.NotConfigured
    }

    override suspend fun parseNotification(request: ParseNotificationRequest, sessionId: String?): ApiResult<ParseNotificationResponse> {
        calls += "parse-notification"
        return ApiResult.NotConfigured
    }

    override suspend fun placePick(request: PlacePickRequest, sessionId: String?): ApiResult<PlacePickResponse> {
        calls += "place-pick"
        return ApiResult.NotConfigured
    }

    override suspend fun event(report: SessionEventReport): ApiResult<Unit> {
        calls += "event"
        return ApiResult.NotConfigured
    }

    override suspend fun erase(): ApiResult<EraseResponse> {
        calls += "erase"
        return ApiResult.NotConfigured
    }

    override suspend fun configCheck(): ApiResult<ConfigCheckResponse> {
        calls += "config-check"
        return ApiResult.NotConfigured
    }

    override suspend fun healthz(): ApiResult<Boolean> = ApiResult.NotConfigured
    override suspend fun readyz(): ApiResult<JsonObject> = ApiResult.NotConfigured
}
