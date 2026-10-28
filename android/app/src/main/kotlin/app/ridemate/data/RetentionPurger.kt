package app.ridemate.data

import androidx.room.withTransaction
import app.ridemate.util.Clock

/** What one purge removed (counts only; logged as metadata). */
data class PurgeReport(
    val closedSessions: Int,
    val abandonedSessions: Int,
    val unscopedFingerprints: Int,
    val unscopedClassifications: Int,
    val finishedOutboxRows: Int,
) {
    val total: Int get() = closedSessions + abandonedSessions + unscopedFingerprints + unscopedClassifications + finishedOutboxRows
}

/**
 * Ride data on the phone is deleted a day after the ride (goal 24, §5): sessions CLOSED more than [retentionMs]
 * ago are deleted, and their legs, quotes, evidence, outbox rows, fingerprints, classifier cache and guardian
 * snapshot go with them through ON DELETE CASCADE. Also removes:
 * - sessions still ACTIVE [abandonedAfterMs] after creation (RideMate was killed for good mid-ride);
 * - fingerprints / classifier entries recorded outside any session, after [retentionMs];
 * - outbox rows that finished (DONE / DEAD) more than [retentionMs] ago.
 *
 * Runs on app start and from [PurgeWorker] (WorkManager, periodic).
 */
class RetentionPurger(
    private val db: RideMateDb,
    private val clock: Clock,
    private val retentionMs: Long = RETENTION_MS,
    private val abandonedAfterMs: Long = ABANDONED_AFTER_MS,
) {
    suspend fun purge(): PurgeReport = db.withTransaction {
        val now = clock.wallMs()
        val cutoff = now - retentionMs
        PurgeReport(
            closedSessions = db.sessionDao().deleteClosedBefore(cutoff),
            abandonedSessions = db.sessionDao().deleteAbandonedBefore(now - abandonedAfterMs),
            unscopedFingerprints = db.postFingerprintDao().deleteUnscopedBefore(cutoff),
            unscopedClassifications = db.classifierCacheDao().deleteUnscopedBefore(cutoff),
            finishedOutboxRows = db.outboxDao().deleteFinishedBefore(cutoff),
        )
    }

    companion object {
        const val RETENTION_MS: Long = 24L * 60 * 60 * 1000
        const val ABANDONED_AFTER_MS: Long = 48L * 60 * 60 * 1000
    }
}
