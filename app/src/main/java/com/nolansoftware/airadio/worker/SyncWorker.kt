package com.nolansoftware.airadio.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nolansoftware.airadio.data.repository.RadioRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val radioRepository: RadioRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "SyncWorker.doWork: starting")
            radioRepository.syncAllData()
            Log.i(TAG, "SyncWorker.doWork: success")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "SyncWorker.doWork: failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "SyncWorker"
        private const val INITIAL_WORK_NAME = "SyncWorker_Initial"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Periodic daily sync — KEEP so we don't reset the schedule on every cold start.
            // WorkManager 2.8.1's Kotlin DSL PeriodicWorkRequestBuilder only accepts
            // (Long, TimeUnit) for the repeat interval; flex interval is not exposed
            // on the Builder, so we omit it (default is no flex — runs at the exact
            // repeat boundary).
            val periodicRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )

            // One-time immediate sync on every cold start. WorkManager periodic workers
            // wait at least 15 minutes before their first execution, so without this the
            // Room cache stays empty and every screen reads "Loading..." until then.
            // REPLACE cancels any pending initial from a previous launch and re-enqueues.
            val initialRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                INITIAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                initialRequest
            )
        }
    }
}