package app.ridemate.outbox

import app.ridemate.crash.CrashReporter
import app.ridemate.crash.CrashSite
import app.ridemate.data.OutboxKind
import app.ridemate.util.Clock
import app.ridemate.util.SessionActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** What a handler did with one due item. */
sealed interface OutboxOutcome {
    /** Delivered: the row is completed. */
    data object Done : OutboxOutcome

    /** Not now: try again [afterMs] from now (counts an attempt). */
    data class Retry(val afterMs: Long) : OutboxOutcome

    /** Never deliverable (leg gone, account changed): the row is marked DEAD. */
    data object Dead : OutboxOutcome
}

/**
 * Delivers due outbox items of the kinds it is registered for. A3 implements REMINDER / FOLLOWUP_CHECK /
 * OPEN_NEXT_APP (bound to the guardian); the platform registers ERASE_SERVER. Called on the driver's coroutine;
 * must not block for long (it runs inside the service's 1 s tick).
 */
fun interface OutboxHandler {
    suspend fun handle(item: OutboxItem): OutboxOutcome
}

/** Counts of one [OutboxDriver.tick]. */
data class TickReport(
    val delivered: Int = 0,
    val retried: Int = 0,
    val dead: Int = 0,
    /** Due items whose kind has no registered handler yet (left pending). */
    val unhandled: Int = 0,
    val nextDueElapsedMs: Long? = null,
)

/** Schedules the WorkManager backstop (production: [WorkManagerOutboxScheduler]). */
interface OutboxWorkScheduler {
    /** Run the outbox worker at [dueElapsedMs] (now if past); [needsNetwork] adds a connectivity constraint. */
    fun scheduleAt(dueElapsedMs: Long, needsNetwork: Boolean)

    fun cancel()
}

/**
 * Fires due outbox items (§4.2 ActionOutbox, I8/M7):
 * - while a session runs ([SessionActivity.active]), the foreground service calls [tick] every second and the
 *   WorkManager backstop is cancelled, so reminders never depend on WorkManager under Doze;
 * - otherwise [start] keeps one WorkManager job scheduled at the next due time, and [OutboxWorker] calls [tick].
 *
 * Handler failures (exceptions) are retried with backoff; an item is marked DEAD after [maxAttempts].
 */
class OutboxDriver(
    private val repository: OutboxRepository,
    private val clock: Clock,
    private val sessionActivity: SessionActivity,
    private val crash: CrashReporter? = null,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private val handlers = ConcurrentHashMap<OutboxKind, OutboxHandler>()
    private val tickMutex = Mutex()

    /** Registers [handler] for [kinds] (replacing any previous one). Close the result to unregister. */
    fun register(kinds: Set<OutboxKind>, handler: OutboxHandler): AutoCloseable {
        kinds.forEach { handlers[it] = handler }
        return AutoCloseable { kinds.forEach { handlers.remove(it, handler) } }
    }

    fun hasHandler(kind: OutboxKind): Boolean = handlers.containsKey(kind)

    /** Delivers every due item once. Overlapping calls are serialized. */
    suspend fun tick(): TickReport = tickMutex.withLock {
        var delivered = 0
        var retried = 0
        var dead = 0
        var unhandled = 0
        val now = clock.elapsedRealtimeMs()
        for (item in repository.due(now)) {
            val handler = handlers[item.kind]
            if (handler == null) {
                unhandled++
                continue
            }
            val outcome = try {
                handler.handle(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                crash?.recordNonFatal(e, CrashSite.OUTBOX)
                OutboxOutcome.Retry(backoffMs(item.attempts))
            }
            when (outcome) {
                OutboxOutcome.Done -> {
                    repository.complete(item)
                    delivered++
                }
                OutboxOutcome.Dead -> {
                    repository.kill(item)
                    dead++
                }
                is OutboxOutcome.Retry -> if (item.attempts + 1 >= maxAttempts) {
                    repository.kill(item)
                    dead++
                } else {
                    repository.retryLater(item, outcome.afterMs)
                    retried++
                }
            }
        }
        TickReport(delivered, retried, dead, unhandled, repository.nextDueElapsedMs())
    }

    /**
     * Keeps the WorkManager backstop in step with the pending rows and the session flag, for the life of [scope].
     * Also flushes memory-held rows into Room when possible.
     */
    fun start(scope: CoroutineScope, scheduler: OutboxWorkScheduler): Job =
        combine(sessionActivity.active, repository.observePending()) { active, pending -> active to pending }
            .onEach { (active, pending) ->
                if (active || pending.isEmpty()) {
                    scheduler.cancel()
                } else {
                    val next = pending.minOf { it.dueElapsedMs }
                    val earliest = pending.filter { it.dueElapsedMs == next }
                    scheduler.scheduleAt(next, needsNetwork = earliest.all { it.kind == OutboxKind.ERASE_SERVER })
                }
                if (pending.any { it.inMemoryOnly }) repository.flushMemory()
            }
            .launchIn(scope)

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 20

        /** 30 s, 60 s, 120 s … capped at 1 h. */
        fun backoffMs(attempts: Int): Long = (30_000L shl attempts.coerceIn(0, 7)).coerceAtMost(3_600_000L)
    }
}
