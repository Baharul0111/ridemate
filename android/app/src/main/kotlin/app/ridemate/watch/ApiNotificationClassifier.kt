package app.ridemate.watch

import app.ridemate.auth.AuthState
import app.ridemate.core.mask.PiiDetector
import app.ridemate.core.mask.maskText
import app.ridemate.core.model.Provider
import app.ridemate.core.watch.ClassifierLabel
import app.ridemate.core.watch.ClassifierResult
import app.ridemate.core.watch.ClassifyRequest
import app.ridemate.core.watch.NotificationClassifier
import app.ridemate.core.watch.TextMasker
import app.ridemate.core.watch.UnavailableReason
import app.ridemate.net.ApiClient
import app.ridemate.net.ApiResult
import app.ridemate.net.ParseNotificationRequest
import java.io.IOException

/** RideWatch's [TextMasker]: the PII spans core `mask` finds (phones, OTP/PIN, plates, names, emails). */
class PiiTextMasker(private val detector: PiiDetector = PiiDetector()) : TextMasker {
    override fun spans(text: String): List<IntRange> = detector.detect(text).map { it.start until it.end }
}

/** A classifier call that failed in a way RideWatch should record as CLASSIFIER_FAILED (timeouts, server errors). */
class ClassifierCallFailed(reason: String) : IOException(reason)

/**
 * The rule-miss classifier (§4.5.5) over `POST /v1/parse-notification`.
 *
 * - Only during an active session ([activeSessionId] non-null) and only for the three live ride apps: demo posts and
 *   posts outside a session are never sent (§4.5.10).
 * - Anonymous or signed-out users and a disabled AI kill switch get [ClassifierResult.Unavailable] without a network
 *   call, so RideWatch falls back to a MAYBE prompt at once.
 * - Title and text arrive masked by RideWatch; they are masked again here with core `mask` before upload.
 * - The label may only raise a prompt or propose SEARCHING/ASSIGNED; core decides that ([ClassifierLabel]).
 */
class ApiNotificationClassifier(
    private val api: ApiClient,
    private val authState: () -> AuthState,
    private val aiEnabled: () -> Boolean,
    private val activeSessionId: () -> String?,
) : NotificationClassifier {

    override suspend fun classify(request: ClassifyRequest): ClassifierResult {
        val sessionId = activeSessionId() ?: return ClassifierResult.Unavailable(UnavailableReason.KILL_SWITCH)
        val provider = Provider.fromWireId(request.app)?.takeIf { it in Provider.LIVE_APPS }
            ?: return ClassifierResult.Unavailable(UnavailableReason.KILL_SWITCH)
        when (authState()) {
            AuthState.SignedOut -> return ClassifierResult.Unavailable(UnavailableReason.SIGNED_OUT)
            is AuthState.Anonymous -> return ClassifierResult.Unavailable(UnavailableReason.ANONYMOUS)
            is AuthState.Google -> Unit
        }
        if (!aiEnabled()) return ClassifierResult.Unavailable(UnavailableReason.KILL_SWITCH)
        val call = ParseNotificationRequest(
            provider = provider,
            versionCode = request.versionCode ?: 0L,
            titleMasked = maskText(request.title),
            textMasked = maskText(request.text),
            channelId = request.channelId,
            category = request.category,
            isOngoing = request.isOngoing,
            legState = request.legState,
        )
        return when (val result = api.parseNotification(call, sessionId)) {
            is ApiResult.Ok -> ClassifierResult.Labelled(ClassifierLabel.fromWire(result.body.label))
            is ApiResult.AiPaused -> ClassifierResult.Unavailable(
                when (result.code) {
                    "quota_exhausted" -> UnavailableReason.QUOTA
                    "ai_unavailable" -> UnavailableReason.BREAKER_OPEN
                    else -> UnavailableReason.AI_PAUSED
                },
            )
            ApiResult.AuthNeeded -> ClassifierResult.Unavailable(UnavailableReason.SIGNED_OUT)
            ApiResult.GoogleRequired -> ClassifierResult.Unavailable(UnavailableReason.ANONYMOUS)
            is ApiResult.RateLimited -> ClassifierResult.Unavailable(UnavailableReason.QUOTA)
            ApiResult.Offline, ApiResult.NotConfigured -> ClassifierResult.Unavailable(UnavailableReason.OFFLINE)
            ApiResult.Timeout -> throw ClassifierCallFailed("timeout")
            is ApiResult.Http -> throw ClassifierCallFailed("http ${result.code}")
        }
    }
}
