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

/**
 * Periodic 24 h retention purge (goal 24): ride data in Room ([RetentionPurger]) and the recent destinations kept in
 * preferences ([app.ridemate.trip.RecentPlacesStore]), so neither outlives a day just because RideMate was not opened
 * again. Dependencies come from the app's [app.ridemate.AppGraph].
 */
class PurgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as? RideMateApp)?.graph ?: return Result.failure()
        // Recent destinations first and on their own: a Room failure must not keep typed or spoken addresses.
        try {
            graph.recentPlaces.purgeExpired()
        } catch (e: Exception) {
            graph.crashReporter.recordNonFatal(e, CrashSite.PURGE)
        }
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
