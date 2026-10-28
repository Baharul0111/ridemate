package app.ridemate.outbox

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ridemate.RideMateApp
import app.ridemate.crash.CrashSite
import app.ridemate.util.Clock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The outbox backstop when no session runs: fires due rows (and waits for background deliveries such as the queued
 * server erase), then asks [OutboxDriver.onBackstopRun] to plan the next job. While a session is active it does
 * nothing (the foreground service ticks the outbox itself).
 *
 * The tick runs non-cancellably: rescheduling (REPLACE) while this worker runs must not interrupt a delivery.
 */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as? RideMateApp)?.graph ?: return Result.failure()
        if (graph.sessionActivity.active.value) return Result.success()
        return try {
            withContext(NonCancellable) {
                graph.outboxRepository.rebaseAfterReboot()
                graph.outboxDriver.tick()
                graph.outboxDriver.awaitBackground()
            }
            graph.outboxDriver.onBackstopRun()
            Result.success()
        } catch (e: Exception) {
            graph.crashReporter.recordNonFatal(e, CrashSite.OUTBOX)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "ridemate-outbox"
    }
}

/** [OutboxWorkScheduler] with one unique one-time WorkManager job (re-enqueued only when the target changes). */
class WorkManagerOutboxScheduler(
    private val workManager: () -> WorkManager,
    private val clock: Clock,
) : OutboxWorkScheduler {

    private data class Target(val dueElapsedMs: Long, val needsNetwork: Boolean)

    /** Last request sent in this process: a [Target], [CANCELLED], or null (unknown: a previous process may have one). */
    @Volatile
    private var last: Any? = null

    @Synchronized
    override fun scheduleAt(dueElapsedMs: Long, needsNetwork: Boolean) {
        val target = Target(dueElapsedMs, needsNetwork)
        if (target == last) return
        val delay = (dueElapsedMs - clock.elapsedRealtimeMs()).coerceAtLeast(0)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (needsNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()
        workManager().enqueueUniqueWork(OutboxWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        last = target
    }

    @Synchronized
    override fun cancel() {
        if (last === CANCELLED) return
        workManager().cancelUniqueWork(OutboxWorker.UNIQUE_NAME)
        last = CANCELLED
    }

    @Synchronized
    override fun invalidate() {
        last = null
    }

    private companion object {
        val CANCELLED = Any()
    }
}
