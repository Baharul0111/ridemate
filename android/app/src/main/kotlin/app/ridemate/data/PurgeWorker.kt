package app.ridemate.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ridemate.RideMateApp
import app.ridemate.crash.CrashSite
import java.util.concurrent.TimeUnit

/** Periodic 24 h retention purge (see [RetentionPurger]). Dependencies come from the app's [app.ridemate.AppGraph]. */
class PurgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as? RideMateApp)?.graph ?: return Result.failure()
        return try {
            graph.retentionPurger.purge()
            Result.success()
        } catch (e: Exception) {
            graph.crashReporter.recordNonFatal(e, CrashSite.PURGE)
            if (StorageHealth.isStorageFailure(e)) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME = "ridemate-retention-purge"
        const val INTERVAL_HOURS = 6L

        /** Idempotent: keeps an already scheduled purge. */
        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<PurgeWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
