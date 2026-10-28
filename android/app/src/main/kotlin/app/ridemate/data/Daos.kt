package app.ridemate.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * DAOs. Reads that the UI or the guardian watch are Flow queries (they re-emit on every change of the tables they
 * touch); one-shot reads and all writes are suspend functions and run on Room's own executors.
 */

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: SessionEntity)

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Query("SELECT * FROM session WHERE id = :id")
    fun observe(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM session WHERE status = 'ACTIVE' ORDER BY created_at_utc DESC")
    fun observeActive(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM session WHERE status = 'ACTIVE' ORDER BY created_at_utc DESC")
    suspend fun active(): List<SessionEntity>

    @Query("SELECT * FROM session ORDER BY created_at_utc DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    /** Closes an ACTIVE session; returns 1 if it was active, else 0 (idempotent). */
    @Query("UPDATE session SET status = 'CLOSED', closed_at_utc = :closedAtUtc WHERE id = :id AND status = 'ACTIVE'")
    suspend fun close(id: String, closedAtUtc: Long): Int

    @Query("UPDATE session SET kept_leg_id = :legId WHERE id = :id")
    suspend fun setKeptLeg(id: String, legId: String?)

    /** Deletes CLOSED sessions closed before [cutoffUtc] (children cascade). */
    @Query("DELETE FROM session WHERE status = 'CLOSED' AND closed_at_utc < :cutoffUtc")
    suspend fun deleteClosedBefore(cutoffUtc: Long): Int

    /** Deletes sessions left ACTIVE (e.g. the app was killed for good) that were created before [cutoffUtc]. */
    @Query("DELETE FROM session WHERE status = 'ACTIVE' AND created_at_utc < :cutoffUtc")
    suspend fun deleteAbandonedBefore(cutoffUtc: Long): Int

    @Query("SELECT COUNT(*) FROM session")
    suspend fun count(): Int

    @Query("SELECT MAX(created_at_utc) FROM session")
    suspend fun lastCreatedAtUtc(): Long?
}

@Dao
interface LegDao {
    @Upsert
    suspend fun upsert(leg: LegEntity)

    @Upsert
    suspend fun upsertAll(legs: List<LegEntity>)

    @Query("SELECT * FROM leg WHERE id = :id")
    suspend fun get(id: String): LegEntity?

    @Query("SELECT * FROM leg WHERE id = :id")
    fun observe(id: String): Flow<LegEntity?>

    @Query("SELECT * FROM leg WHERE session_id = :sessionId ORDER BY rowid")
    suspend fun forSession(sessionId: String): List<LegEntity>

    @Query("SELECT * FROM leg WHERE session_id = :sessionId ORDER BY rowid")
    fun observeForSession(sessionId: String): Flow<List<LegEntity>>

    @Query("SELECT COUNT(*) FROM leg")
    suspend fun count(): Int
}

@Dao
interface QuoteDao {
    @Insert
    suspend fun insert(quote: QuoteEntity): Long

    @Insert
    suspend fun insertAll(quotes: List<QuoteEntity>): List<Long>

    @Query("SELECT * FROM quote WHERE leg_id = :legId ORDER BY seen_at_utc DESC, id DESC")
    fun observeForLeg(legId: String): Flow<List<QuoteEntity>>

    @Query(
        "SELECT quote.* FROM quote INNER JOIN leg ON leg.id = quote.leg_id " +
            "WHERE leg.session_id = :sessionId ORDER BY quote.seen_at_utc DESC, quote.id DESC",
    )
    fun observeForSession(sessionId: String): Flow<List<QuoteEntity>>

    @Query("DELETE FROM quote WHERE leg_id = :legId")
    suspend fun deleteForLeg(legId: String): Int

    @Query("SELECT COUNT(*) FROM quote")
    suspend fun count(): Int
}

@Dao
interface EvidenceDao {
    @Insert
    suspend fun insert(evidence: EvidenceEntity): Long

    @Query("SELECT * FROM evidence WHERE leg_id = :legId ORDER BY at_utc, id")
    suspend fun forLeg(legId: String): List<EvidenceEntity>

    @Query("SELECT * FROM evidence WHERE leg_id = :legId ORDER BY at_utc, id")
    fun observeForLeg(legId: String): Flow<List<EvidenceEntity>>

    @Query("SELECT COUNT(*) FROM evidence")
    suspend fun count(): Int
}

@Dao
interface ActionOutboxDao {
    @Insert
    suspend fun insert(row: ActionOutboxEntity): Long

    @Update
    suspend fun update(row: ActionOutboxEntity)

    @Query("SELECT * FROM action_outbox WHERE id = :id")
    suspend fun get(id: Long): ActionOutboxEntity?

    @Query("SELECT * FROM action_outbox WHERE status = 'PENDING' ORDER BY due_elapsed_ms, id")
    suspend fun pending(): List<ActionOutboxEntity>

    @Query("SELECT * FROM action_outbox WHERE status = 'PENDING' ORDER BY due_elapsed_ms, id")
    fun observePending(): Flow<List<ActionOutboxEntity>>

    @Query("SELECT * FROM action_outbox WHERE status = 'PENDING' AND due_elapsed_ms <= :nowElapsedMs ORDER BY due_elapsed_ms, id")
    suspend fun due(nowElapsedMs: Long): List<ActionOutboxEntity>

    @Query("SELECT * FROM action_outbox WHERE status = 'PENDING' AND boot_id != :bootId")
    suspend fun pendingFromOtherBoot(bootId: String): List<ActionOutboxEntity>

    @Query("UPDATE action_outbox SET status = 'DONE' WHERE leg_id = :legId AND status = 'PENDING' AND kind IN (:kinds)")
    suspend fun completeForLeg(legId: String, kinds: List<OutboxKind>): Int

    @Query("UPDATE action_outbox SET status = 'DONE' WHERE id = :id AND status = 'PENDING'")
    suspend fun complete(id: Long): Int

    @Query("DELETE FROM action_outbox WHERE status != 'PENDING' AND created_at_utc < :cutoffUtc")
    suspend fun deleteFinishedBefore(cutoffUtc: Long): Int

    @Query("SELECT COUNT(*) FROM action_outbox WHERE status = 'PENDING' AND kind = :kind")
    suspend fun countPending(kind: OutboxKind): Int

    @Query("SELECT COUNT(*) FROM action_outbox")
    suspend fun count(): Int
}

@Dao
interface PostFingerprintDao {
    @Upsert
    suspend fun upsert(row: PostFingerprintEntity)

    @Query("SELECT * FROM post_fingerprint WHERE `key` = :key")
    suspend fun get(key: String): PostFingerprintEntity?

    @Query("DELETE FROM post_fingerprint WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: String): Int

    /** Rows outside any session are kept only for [cutoffUtc]-bounded windows. */
    @Query("DELETE FROM post_fingerprint WHERE session_id IS NULL AND last_seen < :cutoffUtc")
    suspend fun deleteUnscopedBefore(cutoffUtc: Long): Int

    @Query("SELECT COUNT(*) FROM post_fingerprint")
    suspend fun count(): Int
}

@Dao
interface ClassifierCacheDao {
    @Upsert
    suspend fun upsert(row: ClassifierCacheEntity)

    @Query(
        "SELECT * FROM classifier_cache WHERE package_name = :packageName AND version_code = :versionCode " +
            "AND normalised_text = :normalisedText",
    )
    suspend fun get(packageName: String, versionCode: Long, normalisedText: String): ClassifierCacheEntity?

    @Query("DELETE FROM classifier_cache WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: String): Int

    @Query("DELETE FROM classifier_cache WHERE session_id IS NULL AND created_at_utc < :cutoffUtc")
    suspend fun deleteUnscopedBefore(cutoffUtc: Long): Int

    @Query("SELECT COUNT(*) FROM classifier_cache")
    suspend fun count(): Int
}

@Dao
interface GuardianSnapshotDao {
    @Upsert
    suspend fun upsert(row: GuardianSnapshotEntity)

    @Query("SELECT * FROM guardian_snapshot WHERE session_id = :sessionId")
    suspend fun get(sessionId: String): GuardianSnapshotEntity?

    @Query("SELECT * FROM guardian_snapshot WHERE session_id = :sessionId")
    fun observe(sessionId: String): Flow<GuardianSnapshotEntity?>

    /** Snapshots of sessions that are still ACTIVE (restored on process start). */
    @Query(
        "SELECT guardian_snapshot.* FROM guardian_snapshot INNER JOIN session ON session.id = guardian_snapshot.session_id " +
            "WHERE session.status = 'ACTIVE' ORDER BY guardian_snapshot.updated_at_utc DESC",
    )
    suspend fun forActiveSessions(): List<GuardianSnapshotEntity>

    @Query("DELETE FROM guardian_snapshot WHERE session_id = :sessionId")
    suspend fun delete(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM guardian_snapshot")
    suspend fun count(): Int
}

@Dao
interface RecorderPostDao {
    @Insert
    suspend fun insert(row: RecorderPostEntity): Long

    @Query("SELECT * FROM recorder_post ORDER BY at_utc, id")
    suspend fun all(): List<RecorderPostEntity>

    @Query("SELECT * FROM recorder_post ORDER BY at_utc DESC, id DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<RecorderPostEntity>>

    @Query("DELETE FROM recorder_post")
    suspend fun clear(): Int

    @Query("SELECT COUNT(*) FROM recorder_post")
    suspend fun count(): Int
}
