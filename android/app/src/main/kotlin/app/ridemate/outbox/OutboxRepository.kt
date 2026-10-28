package app.ridemate.outbox

import app.ridemate.data.ActionOutboxDao
import app.ridemate.data.ActionOutboxEntity
import app.ridemate.data.OutboxKind
import app.ridemate.data.OutboxStatus
import app.ridemate.data.StorageHealth
import app.ridemate.util.BootIdProvider
import app.ridemate.util.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One pending action. [id] > 0 for rows in Room, < 0 for rows held in memory because Room could not take the write.
 * [dueElapsedMs] is on the current boot's `SystemClock.elapsedRealtime()`.
 */
data class OutboxItem(
    val id: Long,
    val legId: String?,
    val kind: OutboxKind,
    val dueElapsedMs: Long,
    val attempts: Int,
    val payload: String?,
) {
    val inMemoryOnly: Boolean get() = id < 0
}

/**
 * The ActionOutbox (§4.2): pending reminders, follow-up checks, next-app openings and server erases, persisted in
 * Room so they survive process death.
 *
 * - Due times use elapsed realtime; each row also stores the wall-clock instant and the boot id, and
 *   [rebaseAfterReboot] moves rows written in an earlier boot onto the current one (wall-clock delta).
 * - If Room cannot take a write (disk full, I/O error), the change is kept in memory, [StorageHealth] raises the
 *   "storage full" warning, and [flushMemory] writes it to Room once writes work again. Reads always reflect the
 *   in-memory changes, so a row whose "done" could not be written is never delivered twice by this process.
 */
class OutboxRepository(
    private val dao: ActionOutboxDao,
    private val clock: Clock,
    private val bootIds: BootIdProvider,
    private val storage: StorageHealth,
) {
    private val mutex = Mutex()

    /** Rows Room could not insert. */
    private val memory = MutableStateFlow<List<OutboxItem>>(emptyList())

    /** Room rows whose final status could not be written yet: id → status to write. Hidden from reads. */
    private val unwritten = MutableStateFlow<Map<Long, OutboxStatus>>(emptyMap())

    private var nextMemoryId = -1L

    /** Schedules [kind] to fire [delayMs] from now. */
    suspend fun schedule(kind: OutboxKind, legId: String?, delayMs: Long, payload: String? = null): OutboxItem =
        scheduleAt(kind, legId, clock.elapsedRealtimeMs() + delayMs.coerceAtLeast(0), payload)

    /** Schedules [kind] at an absolute elapsed-realtime instant. */
    suspend fun scheduleAt(kind: OutboxKind, legId: String?, dueElapsedMs: Long, payload: String? = null): OutboxItem =
        mutex.withLock { insertOrKeep(newEntity(kind, legId, dueElapsedMs, payload)) }

    /** All pending items (Room + memory), earliest first. */
    suspend fun pending(): List<OutboxItem> = merge(readDisk { dao.pending() }, memory.value, unwritten.value)

    fun observePending(): Flow<List<OutboxItem>> =
        combine(dao.observePending(), memory, unwritten) { disk, mem, hidden -> merge(disk, mem, hidden) }

    /** Pending items due at or before [nowElapsedMs]. */
    suspend fun due(nowElapsedMs: Long = clock.elapsedRealtimeMs()): List<OutboxItem> =
        merge(readDisk { dao.due(nowElapsedMs) }, memory.value, unwritten.value).filter { it.dueElapsedMs <= nowElapsedMs }

    suspend fun nextDueElapsedMs(): Long? = pending().minOfOrNull { it.dueElapsedMs }

    /** Marks [item] delivered. */
    suspend fun complete(item: OutboxItem) = finish(item, OutboxStatus.DONE)

    /** Gives up on [item] (kept as DEAD until the 24 h purge). */
    suspend fun kill(item: OutboxItem) = finish(item, OutboxStatus.DEAD)

    /** Counts a failed attempt and moves the due time [delayMs] from now. */
    suspend fun retryLater(item: OutboxItem, delayMs: Long): Unit = mutex.withLock {
        val due = clock.elapsedRealtimeMs() + delayMs.coerceAtLeast(0)
        if (item.inMemoryOnly) {
            memory.value = memory.value.map { if (it.id == item.id) it.copy(dueElapsedMs = due, attempts = it.attempts + 1) else it }
            return@withLock
        }
        val row = readDiskRow(item.id) ?: return@withLock
        val moved = row.copy(attempts = row.attempts + 1, dueElapsedMs = due, dueWallMs = wallFor(due), bootId = bootIds.currentBootId())
        try {
            dao.update(moved)
            storage.onWriteSucceeded()
        } catch (e: Exception) {
            if (!StorageHealth.isStorageFailure(e)) throw e
            storage.onWriteFailed(e)
            // Replace the stale disk row by an in-memory copy with the new due time.
            unwritten.value = unwritten.value + (row.id to OutboxStatus.DONE)
            memory.value = memory.value + moved.toItem().copy(id = nextMemoryId--)
        }
    }

    /** Completes every pending row of [legId] of the given kinds (the leg ended or the user dismissed it). */
    suspend fun completeForLeg(legId: String, kinds: Collection<OutboxKind> = LEG_KINDS): Unit = mutex.withLock {
        memory.value = memory.value.filterNot { it.legId == legId && it.kind in kinds }
        try {
            dao.completeForLeg(legId, kinds.toList())
        } catch (e: Exception) {
            if (!StorageHealth.isStorageFailure(e)) throw e
            storage.onWriteFailed(e)
            val ids = readDisk { dao.pending() }.filter { it.legId == legId && it.kind in kinds }.map { it.id }
            unwritten.value = unwritten.value + ids.associateWith { OutboxStatus.DONE }
        }
    }

    suspend fun countPending(kind: OutboxKind): Int = pending().count { it.kind == kind }

    /**
     * Moves rows written during an earlier boot onto this boot's elapsed-realtime base, keeping their wall-clock
     * due instant (an overdue row becomes due now). Call once per process start. Returns the number of rows moved.
     */
    suspend fun rebaseAfterReboot(): Int = mutex.withLock {
        val boot = bootIds.currentBootId()
        val stale = dao.pendingFromOtherBoot(boot)
        val nowElapsed = clock.elapsedRealtimeMs()
        val nowWall = clock.wallMs()
        stale.forEach { row ->
            val due = (nowElapsed + (row.dueWallMs - nowWall)).coerceAtLeast(0)
            dao.update(row.copy(dueElapsedMs = due, bootId = boot))
        }
        stale.size
    }

    /**
     * Writes in-memory changes to Room once it accepts writes again (pending statuses first, then memory rows).
     * Returns how many changes were written; stops at the first storage failure.
     */
    suspend fun flushMemory(): Int = mutex.withLock {
        var written = 0
        for ((id, status) in unwritten.value) {
            try {
                dao.get(id)?.let { dao.update(it.copy(status = status)) }
                unwritten.value = unwritten.value - id
                written++
            } catch (e: Exception) {
                if (!StorageHealth.isStorageFailure(e)) throw e
                storage.onWriteFailed(e)
                return@withLock written
            }
        }
        for (item in memory.value) {
            val entity = newEntity(item.kind, item.legId, item.dueElapsedMs, item.payload).copy(attempts = item.attempts)
            try {
                dao.insert(entity)
                written++
            } catch (e: Exception) {
                if (StorageHealth.isStorageFailure(e)) {
                    storage.onWriteFailed(e)
                    return@withLock written
                }
                // Constraint failure: the leg was deleted meanwhile, so the action is moot.
            }
            memory.value = memory.value.filterNot { it.id == item.id }
        }
        if (written > 0) storage.onWriteSucceeded()
        written
    }

    /** Drops in-memory state (erase; Room rows are wiped with the database). */
    suspend fun clearMemory() = mutex.withLock {
        memory.value = emptyList()
        unwritten.value = emptyMap()
    }

    private suspend fun finish(item: OutboxItem, status: OutboxStatus): Unit = mutex.withLock {
        if (item.inMemoryOnly) {
            memory.value = memory.value.filterNot { it.id == item.id }
            return@withLock
        }
        val row = readDiskRow(item.id) ?: return@withLock
        try {
            dao.update(row.copy(status = status))
            storage.onWriteSucceeded()
        } catch (e: Exception) {
            if (!StorageHealth.isStorageFailure(e)) throw e
            storage.onWriteFailed(e)
            unwritten.value = unwritten.value + (row.id to status)
        }
    }

    private suspend fun insertOrKeep(entity: ActionOutboxEntity): OutboxItem = try {
        val id = dao.insert(entity)
        storage.onWriteSucceeded()
        entity.copy(id = id).toItem()
    } catch (e: Exception) {
        if (!StorageHealth.isStorageFailure(e)) throw e
        storage.onWriteFailed(e)
        val item = entity.toItem().copy(id = nextMemoryId--)
        memory.value = memory.value + item
        item
    }

    private suspend fun readDisk(block: suspend () -> List<ActionOutboxEntity>): List<ActionOutboxEntity> = try {
        block()
    } catch (e: Exception) {
        if (!StorageHealth.isStorageFailure(e)) throw e
        emptyList()
    }

    private suspend fun readDiskRow(id: Long): ActionOutboxEntity? = try {
        dao.get(id)
    } catch (e: Exception) {
        if (!StorageHealth.isStorageFailure(e)) throw e
        null
    }

    private fun merge(disk: List<ActionOutboxEntity>, mem: List<OutboxItem>, hidden: Map<Long, OutboxStatus>): List<OutboxItem> =
        (disk.filter { it.id !in hidden }.map { it.toItem() } + mem).sortedWith(ORDER)

    private fun ActionOutboxEntity.toItem() = OutboxItem(id, legId, kind, dueElapsedMs, attempts, payload)

    private fun newEntity(kind: OutboxKind, legId: String?, dueElapsedMs: Long, payload: String?) = ActionOutboxEntity(
        legId = legId,
        kind = kind,
        dueElapsedMs = dueElapsedMs,
        dueWallMs = wallFor(dueElapsedMs),
        bootId = bootIds.currentBootId(),
        attempts = 0,
        status = OutboxStatus.PENDING,
        payload = payload,
        createdAtUtc = clock.wallMs(),
    )

    private fun wallFor(dueElapsedMs: Long): Long = clock.wallMs() + (dueElapsedMs - clock.elapsedRealtimeMs())

    companion object {
        /** Kinds tied to a leg (everything but ERASE_SERVER). */
        val LEG_KINDS: Set<OutboxKind> = setOf(OutboxKind.REMINDER, OutboxKind.FOLLOWUP_CHECK, OutboxKind.OPEN_NEXT_APP)
        private val ORDER = compareBy<OutboxItem>({ it.dueElapsedMs }, { it.id })
    }
}
