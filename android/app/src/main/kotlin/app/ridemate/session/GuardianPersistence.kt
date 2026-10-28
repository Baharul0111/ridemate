package app.ridemate.session

import app.ridemate.core.guardian.OpenPurpose
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.state.TapId
import app.ridemate.data.EvidenceEntity
import app.ridemate.data.EvidenceSource
import app.ridemate.data.GuardianSnapshotEntity
import app.ridemate.data.GuardianSnapshotStore
import app.ridemate.data.LegEntity
import app.ridemate.data.OutboxKind
import app.ridemate.data.RideMateDb
import app.ridemate.data.SessionEntity
import app.ridemate.data.SessionStatus
import app.ridemate.data.StorageHealth
import app.ridemate.outbox.OutboxRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/** An outbox row the guardian wants pending (a mirror of its own reminder and follow-up schedule). */
data class DesiredRow(val kind: OutboxKind, val legId: String, val dueElapsedMs: Long)

/** OPEN_NEXT_APP payload: ids and enums only. */
@Serializable
data class OpenRequest(val legId: String, val provider: Provider, val purpose: OpenPurpose, val tapId: String? = null) {
    fun encode(): String = PayloadJson.encodeToString(serializer(), this)

    companion object {
        fun decode(raw: String?): OpenRequest? = raw?.let { runCatching { PayloadJson.decodeFromString(serializer(), it) }.getOrNull() }

        fun of(legId: String, provider: Provider, purpose: OpenPurpose, tap: TapId?) = OpenRequest(legId, provider, purpose, tap?.value)
    }
}

/** One evidence rung to store (§4.6). */
data class EvidenceRow(val legId: String, val rung: EvidenceRung, val atUtc: Long)

/**
 * Everything the guardian persists after a step. Writes are idempotent, so consecutive requests can be merged and only
 * the newest state written; [opens] accumulate (each one becomes an OPEN_NEXT_APP row).
 */
data class PersistRequest(
    val version: Long,
    val sessionId: String,
    /** The session row (status, kept leg, closed time already applied). */
    val session: SessionEntity?,
    val legs: List<LegEntity>,
    val snapshotJson: String,
    val evidence: List<EvidenceRow>,
    val desiredOutbox: List<DesiredRow>,
    val opens: List<OpenRequest>,
    val uiJournal: String,
) {
    fun mergedWith(next: PersistRequest): PersistRequest =
        if (next.sessionId == sessionId) next.copy(opens = opens + next.opens) else next
}

/**
 * The guardian's Room side (§4.2 "RideStatechart persistence", ActionOutbox wiring):
 * - the Session and Leg rows (state, generation, driver values, kept/dismissed) for My data and the 24 h purge;
 * - the core Guardian snapshot (guardian_snapshot) that restores reminders and follow-ups after process death;
 * - Evidence rows for every rung that proved a cancel;
 * - ActionOutbox rows mirroring the guardian's reminder and follow-up schedule, so WorkManager can wake the guardian
 *   when no session service runs, plus OPEN_NEXT_APP rows for app openings under the background-start rule.
 * Storage failures never stop the guardian: the snapshot falls back to memory and the outbox to its memory rows.
 */
class GuardianPersistence(
    private val db: RideMateDb,
    private val snapshots: GuardianSnapshotStore,
    private val outbox: OutboxRepository,
    private val storage: StorageHealth,
    private val journal: UiJournalStore,
) {
    suspend fun write(req: PersistRequest) {
        req.session?.let { row -> storageSafe { db.sessionDao().upsert(row) } }
        if (req.legs.isNotEmpty()) storageSafe { db.legDao().upsertAll(req.legs) }
        snapshots.save(req.sessionId, req.snapshotJson)
        storageSafe { writeEvidence(req) }
        syncOutbox(req)
        req.opens.forEach { open ->
            outbox.schedule(OutboxKind.OPEN_NEXT_APP, open.legId, 0L, open.encode())
        }
        if (req.session?.status == SessionStatus.CLOSED) journal.clear() else journal.save(req.sessionId, req.uiJournal)
    }

    /** Snapshots of still-ACTIVE sessions, newest first, with their session rows. */
    suspend fun loadActive(): List<Pair<GuardianSnapshotEntity, SessionEntity?>> =
        snapshots.loadActive().map { it to db.sessionDao().get(it.sessionId) }

    suspend fun session(sessionId: String): SessionEntity? = db.sessionDao().get(sessionId)

    fun journal(sessionId: String): String? = journal.load(sessionId)

    /** Closes sessions the guardian is no longer tracking (an older one left ACTIVE by a crash). */
    suspend fun closeStale(sessionIds: Collection<String>, nowUtc: Long) {
        sessionIds.forEach { id -> storageSafe { db.sessionDao().close(id, nowUtc) } }
    }

    private suspend fun writeEvidence(req: PersistRequest) {
        for ((legId, rows) in req.evidence.groupBy { it.legId }) {
            val stored = db.evidenceDao().forLeg(legId).map { it.rung }.toSet()
            rows.filter { it.rung !in stored }.distinctBy { it.rung }.forEach { row ->
                db.evidenceDao().insert(
                    EvidenceEntity(legId = legId, rung = row.rung, source = sourceOf(row.rung), atUtc = row.atUtc, detailsEnum = row.rung.name),
                )
            }
        }
    }

    /** Keeps exactly the desired REMINDER / FOLLOWUP_CHECK rows of this session's legs pending. */
    private suspend fun syncOutbox(req: PersistRequest) {
        val legIds = req.legs.map { it.id }.toSet()
        val pending = outbox.pending().filter { it.kind in MIRRORED && it.legId in legIds }
        val unmatched = req.desiredOutbox.toMutableList()
        for (row in pending) {
            val match = unmatched.firstOrNull {
                it.kind == row.kind && it.legId == row.legId && abs(it.dueElapsedMs - row.dueElapsedMs) <= DUE_TOLERANCE_MS
            }
            if (match != null) unmatched.remove(match) else outbox.complete(row)
        }
        unmatched.forEach { outbox.scheduleAt(it.kind, it.legId, it.dueElapsedMs) }
    }

    private suspend fun storageSafe(block: suspend () -> Unit) {
        try {
            block()
            storage.onWriteSucceeded()
        } catch (e: Exception) {
            if (!StorageHealth.isStorageFailure(e)) throw e
            storage.onWriteFailed(e)
        }
    }

    companion object {
        val MIRRORED: Set<OutboxKind> = setOf(OutboxKind.REMINDER, OutboxKind.FOLLOWUP_CHECK)
        const val DUE_TOLERANCE_MS = 1_000L

        fun sourceOf(rung: EvidenceRung): EvidenceSource = when (rung) {
            EvidenceRung.ONDC_ON_CANCEL -> EvidenceSource.ONDC
            EvidenceRung.TEMPLATE -> EvidenceSource.TEMPLATE
            EvidenceRung.REMOVAL_PLUS_CAPTURE -> EvidenceSource.REMOVAL_CAPTURE
            EvidenceRung.USER -> EvidenceSource.USER
        }
    }
}

private val PayloadJson = Json { ignoreUnknownKeys = true }
