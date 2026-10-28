package app.ridemate.data

import app.ridemate.util.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Persists the core Guardian's serialized snapshot per session (raw JSON; core `guardian` owns the format).
 * If Room cannot take the write, the latest snapshot is kept in memory and [StorageHealth] raises the storage
 * warning, so the running guardian never fails because the disk is full.
 */
class GuardianSnapshotStore(
    private val dao: GuardianSnapshotDao,
    private val clock: Clock,
    private val storage: StorageHealth,
) {
    private val memory = ConcurrentHashMap<String, GuardianSnapshotEntity>()

    /** Saves [json] as the latest snapshot of [sessionId]. Returns false when it was kept in memory only. */
    suspend fun save(sessionId: String, json: String): Boolean {
        val row = GuardianSnapshotEntity(sessionId, json, clock.wallMs())
        return try {
            dao.upsert(row)
            memory.remove(sessionId)
            storage.onWriteSucceeded()
            true
        } catch (e: Exception) {
            if (!StorageHealth.isStorageFailure(e)) throw e
            memory[sessionId] = row
            storage.onWriteFailed(e)
            false
        }
    }

    /** The newest snapshot of [sessionId] (memory wins: it is only there when newer than the disk copy). */
    suspend fun load(sessionId: String): String? = memory[sessionId]?.json ?: dao.get(sessionId)?.json

    /** Snapshots of every still-ACTIVE session, newest first, for restoring after process death. */
    suspend fun loadActive(): List<GuardianSnapshotEntity> {
        val disk = dao.forActiveSessions().associateBy { it.sessionId }
        return (disk + memory).values.sortedByDescending { it.updatedAtUtc }
    }

    suspend fun delete(sessionId: String) {
        memory.remove(sessionId)
        dao.delete(sessionId)
    }

    /** Drops the in-memory copies (erase). */
    fun clearMemory() = memory.clear()
}
