// SPDX-License-Identifier: Apache-2.0

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
import androidx.work.workDataOf
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
            val fullSync = inputData.getBoolean(KEY_FULL_SYNC, false)
            Log.i(TAG, "SyncWorker.doWork: starting (fullSync=$fullSync)")
            if (fullSync) {
                radioRepository.syncDailyData()
            } else {
                radioRepository.syncInitialData()
            }
            Log.i(TAG, "SyncWorker.doWork: success")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "SyncWorker.doWork: failed (attempt $runAttemptCount)", e)
            // First two failed attempts get retried (WorkManager honors backoff);
            // the third is surfaced as a hard failure so the UI banner offers
            // a manual retry instead of looping forever.
            if (runAttemptCount < MAX_AUTO_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "SyncWorker"
        private const val INITIAL_WORK_NAME = "SyncWorker_Initial"

        /** Per-request flag telling the worker which path to take. */
        private const val KEY_FULL_SYNC = "fullSync"

        /**
         * Number of automatic retries before surfacing a permanent failure to
         * the UI. Bounded to keep an unreachable server / bad DNS from
         * running retries forever in the background.
         */
        private const val MAX_AUTO_RETRIES = 2

        fun schedule(context: Context) {
            // `NetworkType.CONNECTED` requires `NET_CAPABILITY_VALIDATED`, which
            // captive portals / corporate proxies / certain ISPs never set even
            // when packets flow — that constraint silently stranded the
            // SyncWorker on those networks. The repository-level
            // `RegionFailoverSyncExecutor` already handles network failures
            // gracefully (IOException → try next region → eventually surface
            // `SyncState.Failed`), so the constraint here is dropped to
            // `NOT_REQUIRED`. OkHttp's 30/60/120 s timeouts bound a single
            // request; the worker's own retry/backoff (Result.retry, default
            // exponential) bounds the chain.
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
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
                .setInputData(workDataOf(KEY_FULL_SYNC to true))
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
            // fullSync = false so the cold-start payload is small (~300 stations).
            val initialRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_FULL_SYNC to false))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                INITIAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                initialRequest
            )
        }
    }
}