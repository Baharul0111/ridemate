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

/** An [ApiClient] with canned answers: `erase` pops [eraseResults] (default Ok), everything else is NotConfigured. */
class FakeApiClient(private val log: MutableList<String> = mutableListOf()) : ApiClient {
    val eraseResults = ArrayDeque<ApiResult<EraseResponse>>()

    override suspend fun erase(): ApiResult<EraseResponse> {
        log += "api.erase"
        return eraseResults.removeFirstOrNull() ?: ApiResult.Ok(EraseResponse(true))
    }

    override suspend fun readScreen(request: ReadScreenRequest, budget: TapBudget): ApiResult<ReadScreenResponse> = ApiResult.NotConfigured
    override suspend fun reread(request: RereadRequest, budget: TapBudget): ApiResult<RereadResponse> = ApiResult.NotConfigured
    override suspend fun point(request: PointRequest): ApiResult<PointResponse> = ApiResult.NotConfigured
    override suspend fun parseNotification(request: ParseNotificationRequest, sessionId: String?): ApiResult<ParseNotificationResponse> =
        ApiResult.NotConfigured
    override suspend fun placePick(request: PlacePickRequest, sessionId: String?): ApiResult<PlacePickResponse> = ApiResult.NotConfigured
    override suspend fun event(report: SessionEventReport): ApiResult<Unit> = ApiResult.NotConfigured
    override suspend fun configCheck(): ApiResult<ConfigCheckResponse> = ApiResult.NotConfigured
    override suspend fun registerOwnerFcmToken(token: String): ApiResult<Unit> = ApiResult.NotConfigured
    override suspend fun ondcSearch(sessionId: String, pickup: LatLng, drop: LatLng): ApiResult<OndcSearchResponse> = ApiResult.NotConfigured
    override suspend fun ondcSelect(txnId: String, itemId: String): ApiResult<Unit> = ApiResult.NotConfigured
    override suspend fun ondcInit(txnId: String): ApiResult<Unit> = ApiResult.NotConfigured
    override suspend fun ondcConfirm(txnId: String, tapId: String): ApiResult<Unit> = ApiResult.NotConfigured
    override suspend fun ondcStatus(txnId: String): ApiResult<Unit> = ApiResult.NotConfigured
    override suspend fun ondcCancel(txnId: String, tapId: String, confirm: Boolean): ApiResult<Unit> = ApiResult.NotConfigured
    override suspend fun healthz(): ApiResult<Boolean> = ApiResult.NotConfigured
    override suspend fun readyz(): ApiResult<JsonObject> = ApiResult.NotConfigured
}
