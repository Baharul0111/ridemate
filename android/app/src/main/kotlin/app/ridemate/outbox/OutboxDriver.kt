package app.ridemate.outbox

import app.ridemate.crash.CrashReporter
import app.ridemate.crash.CrashSite
import app.ridemate.data.OutboxKind
import app.ridemate.util.Clock
import app.ridemate.util.SessionActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
 * OPEN_NEXT_APP (bound to the guardian); the platform registers ERASE_SERVER. An [OutboxDispatch.INLINE] handler
 * runs inside the session service's 1 s tick and must return quickly; anything that waits on the network is
 * registered with [OutboxDispatch.BACKGROUND].
 */
fun interface OutboxHandler {
    suspend fun handle(item: OutboxItem): OutboxOutcome
}

/** How [OutboxDriver.tick] runs a handler. */
enum class OutboxDispatch {
    /** Inside the tick (reminders, follow-up checks, next-app openings: local and fast). */
    INLINE,

    /**
     * Launched next to the tick, at most one delivery per item at a time, so a slow network call (the queued
     * `/v1/erase`) never holds up a reminder that is due in the same second.
     */
    BACKGROUND,
}

/** Counts of one [OutboxDriver.tick]. */
data class TickReport(
    val delivered: Int = 0,
    val retried: Int = 0,
    val dead: Int = 0,
    /** Due items whose kind has no registered handler yet (left pending). */
    val unhandled: Int = 0,
    /** The earliest due time among pending items not being delivered right now (null: none). */
    val nextDueElapsedMs: Long? = null,
    /** Due items handed to (or still in) a [OutboxDispatch.BACKGROUND] delivery. */
    val inBackground: Int = 0,
)

/** Schedules the WorkManager backstop (production: [WorkManagerOutboxScheduler]). */
interface OutboxWorkScheduler {
    /** Run the outbox worker at [dueElapsedMs] (now if past); [needsNetwork] adds a connectivity constraint. */
    fun scheduleAt(dueElapsedMs: Long, needsNetwork: Boolean)

    fun cancel()

    /** The last scheduled job has run (it no longer exists): the next [scheduleAt] must enqueue even if unchanged. */
    fun invalidate() {}
}

/**
 * Fires due outbox items (§4.2 ActionOutbox, I8/M7):
 * - while a session runs ([SessionActivity.active]), the foreground service calls [tick] every second and the
 *   WorkManager backstop is cancelled, so reminders never depend on WorkManager under Doze;
 * - otherwise [start] keeps one WorkManager job scheduled at the next due time of a kind that has a handler, and
 *   [OutboxWorker] calls [tick], waits for [awaitBackground], then [onBackstopRun] plans the next job.
 *
 * Handler failures (exceptions) are retried with backoff; an item is marked DEAD after [maxAttempts]. Each tick
 * also retries writing rows that Room refused earlier (disk full), so they reach the disk as soon as it has room.
 *
 * @param backgroundScope where [OutboxDispatch.BACKGROUND] deliveries run (the app scope); null runs them inline.
 */
class OutboxDriver(
    private val repository: OutboxRepository,
    private val clock: Clock,
    private val sessionActivity: SessionActivity,
    private val crash: CrashReporter? = null,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val backgroundScope: CoroutineScope? = null,
) {
    private class Registration(val handler: OutboxHandler, val dispatch: OutboxDispatch)

    private enum class Delivery { DELIVERED, RETRIED, DEAD }

    private val handlers = ConcurrentHashMap<OutboxKind, Registration>()
    private val handledKinds = MutableStateFlow<Set<OutboxKind>>(emptySet())
    private val inFlight = ConcurrentHashMap<Long, Job>()
    private val replan = MutableStateFlow(0L)
    private val tickMutex = Mutex()

    @Volatile
    private var scheduler: OutboxWorkScheduler? = null

    /** Registers [handler] for [kinds] (replacing any previous one), run inline. Close the result to unregister. */
    fun register(kinds: Set<OutboxKind>, handler: OutboxHandler): AutoCloseable =
        register(kinds, OutboxDispatch.INLINE, handler)

    /** Registers [handler] for [kinds] (replacing any previous one), run as [dispatch] says. Close to unregister. */
    fun register(kinds: Set<OutboxKind>, dispatch: OutboxDispatch, handler: OutboxHandler): AutoCloseable {
        val registration = Registration(handler, dispatch)
        kinds.forEach { handlers[it] = registration }
        handledKinds.value = handlers.keys.toSet()
        return AutoCloseable {
            kinds.forEach { handlers.remove(it, registration) }
            handledKinds.value = handlers.keys.toSet()
        }
    }

    fun hasHandler(kind: OutboxKind): Boolean = handlers.containsKey(kind)

    /** Delivers every due item once. Overlapping calls are serialized. */
    suspend fun tick(): TickReport = tickMutex.withLock {
        flushUnsaved()
        var delivered = 0
        var retried = 0
        var dead = 0
        var unhandled = 0
        var inBackground = 0
        val now = clock.elapsedRealtimeMs()
        for (item in repository.due(now)) {
            val registration = handlers[item.kind]
            if (registration == null) {
                unhandled++
                continue
            }
            val scope = backgroundScope
            if (registration.dispatch == OutboxDispatch.BACKGROUND && scope != null) {
                if (!inFlight.containsKey(item.id)) launchBackground(scope, item, registration.handler)
                inBackground++
                continue
            }
            when (deliver(item, registration.handler)) {
                Delivery.DELIVERED -> delivered++
                Delivery.RETRIED -> retried++
                Delivery.DEAD -> dead++
            }
        }
        val next = repository.pending().filterNot { inFlight.containsKey(it.id) }.minOfOrNull { it.dueElapsedMs }
        TickReport(delivered, retried, dead, unhandled, next, inBackground)
    }

    /** Waits until every [OutboxDispatch.BACKGROUND] delivery started so far has finished (the backstop worker). */
    suspend fun awaitBackground() {
        while (true) {
            val running = inFlight.values.toList()
            if (running.isEmpty()) return
            running.joinAll()
        }
    }

    /**
     * Keeps the WorkManager backstop in step with the pending rows, the registered handlers and the session flag,
     * for the life of [scope]. Rows of a kind nobody handles yet are not planned for (they would wake the worker
     * for nothing and hide later rows); registering a handler re-plans. Also flushes memory-held rows into Room.
     */
    fun start(scope: CoroutineScope, scheduler: OutboxWorkScheduler): Job {
        this.scheduler = scheduler
        return combine(sessionActivity.active, repository.observePending(), handledKinds, replan) { active, pending, kinds, _ ->
            Plan(active, pending, kinds)
        }
            .onEach { plan -> applyPlan(plan, scheduler) }
            .retryWhen { cause, _ ->
                // The pending-rows query can fail while the database is unavailable: report and watch again.
                if (cause is CancellationException) return@retryWhen false
                crash?.recordNonFatal(cause, CrashSite.OUTBOX)
                delay(PLAN_RETRY_DELAY_MS)
                true
            }
            .launchIn(scope)
    }

    /**
     * The backstop worker ran (its one-time job is gone): plan the next job from the current rows, even if its
     * target equals the last one (a worker woken early, by a clock change, finds nothing due and must not leave
     * the outbox without a backstop).
     */
    fun onBackstopRun() {
        scheduler?.invalidate()
        replan.update { it + 1 }
    }

    private data class Plan(val active: Boolean, val pending: List<OutboxItem>, val kinds: Set<OutboxKind>)

    private suspend fun applyPlan(plan: Plan, scheduler: OutboxWorkScheduler) {
        try {
            val deliverable = plan.pending.filter { it.kind in plan.kinds }
            if (plan.active || deliverable.isEmpty()) {
                scheduler.cancel()
            } else {
                val next = deliverable.minOf { it.dueElapsedMs }
                val earliest = deliverable.filter { it.dueElapsedMs == next }
                scheduler.scheduleAt(next, needsNetwork = earliest.all { it.kind == OutboxKind.ERASE_SERVER })
            }
            if (plan.pending.any { it.inMemoryOnly }) repository.flushMemory()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            crash?.recordNonFatal(e, CrashSite.OUTBOX)
        }
    }

    private fun launchBackground(scope: CoroutineScope, item: OutboxItem, handler: OutboxHandler) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                deliver(item, handler)
            } finally {
                inFlight.remove(item.id, coroutineContext[Job])
            }
        }
        if (inFlight.putIfAbsent(item.id, job) == null) job.start() else job.cancel()
    }

    private suspend fun deliver(item: OutboxItem, handler: OutboxHandler): Delivery {
        val outcome = try {
            handler.handle(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            crash?.recordNonFatal(e, CrashSite.OUTBOX)
            OutboxOutcome.Retry(backoffMs(item.attempts))
        }
        return when (outcome) {
            OutboxOutcome.Done -> {
                repository.complete(item)
                Delivery.DELIVERED
            }
            OutboxOutcome.Dead -> {
                repository.kill(item)
                Delivery.DEAD
            }
            is OutboxOutcome.Retry -> if (item.attempts + 1 >= maxAttempts) {
                repository.kill(item)
                Delivery.DEAD
            } else {
                repository.retryLater(item, outcome.afterMs)
                Delivery.RETRIED
            }
        }
    }

    /** Retries rows Room refused earlier; a failure here must never stop reminders from firing. */
    private suspend fun flushUnsaved() {
        if (!repository.hasUnsavedChanges()) return
        try {
            repository.flushMemory()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            crash?.recordNonFatal(e, CrashSite.OUTBOX)
        }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 20

        /** How long the backstop planner waits before watching the rows again after a database error. */
        const val PLAN_RETRY_DELAY_MS = 30_000L

        /** 30 s, 60 s, 120 s … capped at 1 h. */
        fun backoffMs(attempts: Int): Long = (30_000L shl attempts.coerceIn(0, 7)).coerceAtMost(3_600_000L)
    }
}
