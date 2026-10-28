package app.ridemate.privacy

import app.ridemate.auth.AuthState
import app.ridemate.config.ConfigInfo
import app.ridemate.data.OutboxKind
import app.ridemate.data.RideMateDb
import app.ridemate.outbox.OutboxRepository

/** What RideMate holds on this phone, by kind (the My data screen maps each to a localized label). */
enum class HeldData {
    SESSIONS, LEGS, QUOTES, EVIDENCE, PENDING_ACTIONS, NOTIFICATION_FINGERPRINTS, CLASSIFIER_CACHE,
    GUARDIAN_SNAPSHOTS, RECORDER_POSTS,
}

/** How the user is signed in, without the identity itself. */
enum class SignInKind { SIGNED_OUT, ANONYMOUS, GOOGLE }

/** The My data screen's content (§4.11, goal 24). */
data class MyDataSummary(
    val counts: Map<HeldData, Int>,
    val signInKind: SignInKind,
    /** The Google account's email (shown only to the user, on their own screen). */
    val signInEmail: String?,
    val lastSessionAtUtc: Long?,
    val remoteConfig: ConfigInfo,
    /** An erase is queued until the server answers. */
    val pendingServerErase: Boolean,
) {
    val totalRows: Int get() = counts.values.sum()
}

class MyDataProvider(
    private val db: RideMateDb,
    private val authState: () -> AuthState,
    private val configInfo: () -> ConfigInfo,
    private val outbox: OutboxRepository,
) {
    suspend fun load(): MyDataSummary {
        val counts = linkedMapOf(
            HeldData.SESSIONS to db.sessionDao().count(),
            HeldData.LEGS to db.legDao().count(),
            HeldData.QUOTES to db.quoteDao().count(),
            HeldData.EVIDENCE to db.evidenceDao().count(),
            HeldData.PENDING_ACTIONS to db.outboxDao().count(),
            HeldData.NOTIFICATION_FINGERPRINTS to db.postFingerprintDao().count(),
            HeldData.CLASSIFIER_CACHE to db.classifierCacheDao().count(),
            HeldData.GUARDIAN_SNAPSHOTS to db.guardianSnapshotDao().count(),
            HeldData.RECORDER_POSTS to db.recorderPostDao().count(),
        )
        val state = authState()
        return MyDataSummary(
            counts = counts,
            signInKind = when (state) {
                AuthState.SignedOut -> SignInKind.SIGNED_OUT
                is AuthState.Anonymous -> SignInKind.ANONYMOUS
                is AuthState.Google -> SignInKind.GOOGLE
            },
            signInEmail = (state as? AuthState.Google)?.email,
            lastSessionAtUtc = db.sessionDao().lastCreatedAtUtc(),
            remoteConfig = configInfo(),
            pendingServerErase = outbox.countPending(OutboxKind.ERASE_SERVER) > 0,
        )
    }
}
