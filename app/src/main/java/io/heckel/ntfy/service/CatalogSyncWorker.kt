package io.heckel.ntfy.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.heckel.ntfy.util.Log
import java.util.concurrent.TimeUnit

/**
 * kudcrafts: catalog. Re-fetches the catalog every 15 minutes (with If-None-Match), so a missed
 * sync-topic event costs at most one interval.
 */
class CatalogSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Log.init(applicationContext)
        CatalogSync.sync(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "CatalogSyncWorkerPeriodic"
        private const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val work = PeriodicWorkRequestBuilder<CatalogSyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
