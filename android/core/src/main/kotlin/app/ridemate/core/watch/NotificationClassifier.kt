package app.ridemate.core.watch

import app.ridemate.core.config.NotificationEvent
import app.ridemate.core.model.RideState
import app.ridemate.core.state.ProviderUpdate
import app.ridemate.core.state.RideEvent
import app.ridemate.core.state.SignalSource
import app.ridemate.core.state.UnknownReason
import app.ridemate.core.state.UnknownUpdate

/** Labels of `/v1/parse-notification` (contracts §1.3). */
enum class ClassifierLabel(val wire: String) {
    SEARCHING("searching"),
    ASSIGNED("assigned"),
    ARRIVING("arriving"),
    DRIVER_CANCELLED("driver_cancelled"),
    NO_DRIVERS("no_drivers"),
    CANCELLED("cancelled"),
    ENDED("ended"),
    TRIP_STARTED("trip_started"),
    OTHER("other"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): ClassifierLabel = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/** Masked post fields sent for a rule miss. Title and text are already masked; nothing else is sent. */
data class ClassifyRequest(
    val app: String,
    val versionCode: Long?,
    val title: String,
    val text: String,
    val channelId: String?,
    val category: String?,
    val isOngoing: Boolean,
    val legState: RideState,
) {
    /** B12: even masked notification text stays out of logs and crash reports. */
    override fun toString(): String =
        "ClassifyRequest($app, versionCode=$versionCode, channel=$channelId, category=$category, ongoing=$isOngoing, legState=$legState)"
}

enum class UnavailableReason { SIGNED_OUT, ANONYMOUS, QUOTA, AI_PAUSED, BREAKER_OPEN, KILL_SWITCH, OFFLINE }

sealed interface ClassifierResult {
    data class Labelled(val label: ClassifierLabel) : ClassifierResult
    data class Unavailable(val reason: UnavailableReason) : ClassifierResult
}

/**
 * The rule-miss classifier. The app implements it over `/v1/parse-notification` (and returns [ClassifierResult.Unavailable]
 * without a network call when the user is anonymous or AI is paused). Failures may be thrown; RideWatch turns them,
 * and the 10 s deadline, into UNKNOWN_UPDATE.
 */
fun interface NotificationClassifier {
    suspend fun classify(request: ClassifyRequest): ClassifierResult
}

/**
 * §4.5.6: a label may only raise a MAYBE prompt (UNKNOWN_UPDATE) or propose SEARCHING/ASSIGNED. It never confirms a
 * cancel and never ends a leg, so every other label becomes UNKNOWN_UPDATE.
 */
fun ClassifierLabel.toRideEvent(sbnKey: String, postTimeWallMs: Long, replayed: Boolean): RideEvent = when (this) {
    ClassifierLabel.SEARCHING -> ProviderUpdate(
        NotificationEvent.SEARCHING, SignalSource.CLASSIFIER, sbnKey, postTimeWallMs = postTimeWallMs, replayed = replayed,
    )
    ClassifierLabel.ASSIGNED -> ProviderUpdate(
        NotificationEvent.ASSIGNED, SignalSource.CLASSIFIER, sbnKey, postTimeWallMs = postTimeWallMs, replayed = replayed,
    )
    else -> UnknownUpdate(UnknownReason.CLASSIFIER_LABEL, sbnKey, postTimeWallMs, replayed)
}
