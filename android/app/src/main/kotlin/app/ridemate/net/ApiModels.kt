package app.ridemate.net

import app.ridemate.core.model.LatLng
import app.ridemate.core.model.Provider
import app.ridemate.core.model.RideState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** JSON settings for the API: additive server changes are tolerated (§4.18 J7), nulls are omitted on the wire. */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Request size limits enforced by the server (§4.18 B8); checked on the phone before uploading. */
object ApiLimits {
    /** Image (or all crops of one reread) upper bound. */
    const val MAX_IMAGE_BYTES: Int = 1_000_000

    /** JSON body upper bound. */
    const val MAX_JSON_BYTES: Int = 64 * 1024
}

// ---------------------------------------------------------------------------------------------------------------
// Requests (phone-side inputs). Images must already be masked (§4.2 OcrAndMask) and be JPEG or PNG.
// ---------------------------------------------------------------------------------------------------------------

/** `POST /v1/read-screen` (Channel A). [demo] marks a bundled demo screen (served from the demo cache). */
class ReadScreenRequest(
    val provider: Provider,
    val frameWidth: Int,
    val frameHeight: Int,
    val image: ByteArray,
    val sessionId: String,
    val demo: Boolean = provider.isDemo,
)

/** One disagreeing row crop for `POST /v1/reread`. [rowId] becomes the multipart file field `crop_<rowId>`. */
class RereadCrop(val rowId: String, val image: ByteArray)

class RereadRequest(
    val provider: Provider,
    val crops: List<RereadCrop>,
    val sessionId: String,
)

/** `POST /v1/point` ("Show me" grounding). */
class PointRequest(
    val provider: Provider,
    val image: ByteArray,
    val sessionId: String,
    val target: String = "cancel_button",
)

/** `POST /v1/parse-notification` (rule-miss classifier). Title/text are masked. */
data class ParseNotificationRequest(
    val provider: Provider,
    val versionCode: Long,
    val titleMasked: String?,
    val textMasked: String?,
    val channelId: String?,
    val category: String?,
    val isOngoing: Boolean,
    val legState: RideState,
)

data class PlaceCandidate(val id: String, val label: String)

/** `POST /v1/place-pick`: ≤ 5 Geocoder candidates. */
data class PlacePickRequest(
    val transcript: String,
    val lang: String,
    val candidates: List<PlaceCandidate>,
)

/** `POST /v1/event` (one per session, metadata only). Field names are the wire names. */
@Serializable
data class SessionEventReport(
    @SerialName("session_id") val sessionId: String,
    val app: String,
    @SerialName("version_code") val versionCode: Long,
    /** confirmed_all | dismissed | abandoned | demo */
    val outcome: String,
    /** lt_1m | 1_3m | 3_10m | gt_10m | none */
    @SerialName("time_to_confirm_bucket") val timeToConfirmBucket: String,
    @SerialName("android_version") val androidVersion: Int,
    val reads: Int,
    @SerialName("verified_rows") val verifiedRows: Int,
    @SerialName("unreadable_rows") val unreadableRows: Int,
    @SerialName("rule_hits") val ruleHits: Int,
    @SerialName("rule_misses") val ruleMisses: Int,
    @SerialName("misses_unclassified") val missesUnclassified: Int,
    val demo: Boolean,
)

// ---------------------------------------------------------------------------------------------------------------
// Responses.
// ---------------------------------------------------------------------------------------------------------------

/**
 * `read-screen` 200. [read] is the raw `ChannelARead` object; core `gate` decodes it (it is untrusted model output
 * and is schema-validated there, never displayed directly).
 */
data class ReadScreenResponse(
    val read: JsonObject,
    /** live | cache | recorded */
    val source: String,
    val model: String?,
    val latencyMs: Long?,
    val requestId: String?,
)

/** `reread` 200: one raw row object per re-read crop (`row_id`, `vehicle_class`, `price_min`, …). */
data class RereadResponse(
    val rows: List<JsonObject>,
    val source: String,
    val requestId: String?,
)

/** `point` 200. [box0to1000] = [x0, y0, x1, y1] on a 0–1000 grid; null when the model found nothing. */
data class PointResponse(
    val supported: Boolean,
    val box0to1000: List<Int>?,
)

/** `parse-notification` 200. [label] is the server's enum string (core `watch` maps it). */
data class ParseNotificationResponse(
    val label: String,
    val evidenceSpan: String?,
    val requestId: String?,
)

/** `place-pick` 200. [pick] is a candidate id, or null for `"none"`. */
data class PlacePickResponse(val pick: String?)

@Serializable
data class EraseResponse(val erased: Boolean = false)

@Serializable
data class ConfigCheckQuota(
    @SerialName("reads_left") val readsLeft: Int? = null,
    @SerialName("parses_left") val parsesLeft: Int? = null,
)

/** Owner-only budget view (developer mode). */
@Serializable
data class ConfigCheckBudget(
    @SerialName("ai_spent_usd") val aiSpentUsd: Double? = null,
    @SerialName("ai_budget_usd") val aiBudgetUsd: Double? = null,
    @SerialName("compute_spent_usd") val computeSpentUsd: Double? = null,
    @SerialName("compute_budget_usd") val computeBudgetUsd: Double? = null,
    @SerialName("day_cap_usd") val dayCapUsd: Double? = null,
    @SerialName("day_spent_usd") val daySpentUsd: Double? = null,
    @SerialName("horizon_end") val horizonEnd: String? = null,
)

/** `GET /v1/config-check`. */
@Serializable
data class ConfigCheckResponse(
    @SerialName("ai_enabled") val aiEnabled: Boolean = false,
    /** signed_out | anonymous | kill_switch | budget | day_cap | tier_pause | quota | breaker | meter | model_missing */
    val reason: String? = null,
    @SerialName("resume_at_ist") val resumeAtIst: String? = null,
    @SerialName("point_supported") val pointSupported: Boolean = false,
    val quota: ConfigCheckQuota? = null,
    @SerialName("cap_profile") val capProfile: String? = null,
    @SerialName("min_supported_version") val minSupportedVersion: Int? = null,
    @SerialName("server_time_utc") val serverTimeUtc: String? = null,
    /** True only for the owner's Google account (the server's `OWNER_EMAILS`); the phone ships no owner list. */
    @SerialName("is_owner") val isOwner: Boolean = false,
    val budget: ConfigCheckBudget? = null,
)

/** `POST /v1/ondc/search` 200. */
data class OndcSearchResponse(val txnId: String)

// ---------------------------------------------------------------------------------------------------------------
// Wire bodies (internal).
// ---------------------------------------------------------------------------------------------------------------

@Serializable
internal data class ReadScreenMetaWire(
    val app: String,
    @SerialName("frame_w") val frameW: Int,
    @SerialName("frame_h") val frameH: Int,
    val demo: Boolean,
)

@Serializable
internal data class RereadRowWire(@SerialName("row_id") val rowId: String)

@Serializable
internal data class RereadMetaWire(val app: String, val rows: List<RereadRowWire>)

@Serializable
internal data class PointMetaWire(val app: String, val target: String)

@Serializable
internal data class ParseNotificationWire(
    val app: String,
    @SerialName("version_code") val versionCode: Long,
    val title: String?,
    val text: String?,
    @SerialName("channel_id") val channelId: String?,
    val category: String?,
    @SerialName("is_ongoing") val isOngoing: Boolean,
    @SerialName("leg_state") val legState: String,
)

@Serializable
internal data class PlaceCandidateWire(val id: String, val label: String)

@Serializable
internal data class PlacePickWire(val transcript: String, val lang: String, val candidates: List<PlaceCandidateWire>)

@Serializable
internal data class LatLngWire(val lat: Double, val lng: Double) {
    companion object {
        fun of(p: LatLng) = LatLngWire(p.lat, p.lng)
    }
}

@Serializable
internal data class OndcSearchWire(
    @SerialName("session_id") val sessionId: String,
    val pickup: LatLngWire,
    val drop: LatLngWire,
)

@Serializable
internal data class OndcTxnWire(
    @SerialName("txn_id") val txnId: String,
    @SerialName("item_id") val itemId: String? = null,
    @SerialName("tap_id") val tapId: String? = null,
    val confirm: Boolean? = null,
    val keep: Boolean? = null,
    val abandon: Boolean? = null,
)

@Serializable
internal data class OwnerTokenWire(val token: String)

@Serializable
internal data class ErrorEnvelopeWire(val error: ErrorBodyWire? = null)

@Serializable
internal data class ErrorBodyWire(
    val code: String? = null,
    @SerialName("resume_at_ist") val resumeAtIst: String? = null,
    @SerialName("retry_after_s") val retryAfterS: Long? = null,
    val reason: String? = null,
    @SerialName("request_id") val requestId: String? = null,
)
