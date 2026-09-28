// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nolansoftware.airadio.data.database.dao.PagedStationCacheDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Bounded-size housekeeping for [com.nolansoftware.airadio.data.database.entity.PagedStationCacheEntity].
 * The paging layer writes rows on every scroll; without periodic cleanup the table
 * would grow monotonically across the user's browsing history. This worker drops
 * rows older than the retention window.
 *
 * The retention window (30 days) is intentionally larger than the TTL (7 days) so
 * pages that are still cached can be revalidated against a stale fetched_at before
 * being deleted — there is always a window where a row is "stale, but still useful
 * for offline read."
 *
 * Hilt-injected via [AssistedInject]; scheduled from [com.nolansoftware.airadio.AIRadioApp]
 * with [ExistingPeriodicWorkPolicy.KEEP] so cold starts don't reset the schedule.
 */
@HiltWorker
class CacheCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val dao: PagedStationCacheDao,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
            dao.deleteOlderThan(cutoff)
            Log.i(TAG, "CacheCleanupWorker.doWork: success (cutoff=${cutoff})")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "CacheCleanupWorker.doWork: failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "CacheCleanupWorker"
        private const val WORK_NAME = "CacheCleanupWorker"

        /** 30 days — gives ~23 days of grace past the 7-day TTL before a row is deleted. */
        const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** 24 hours — WorkManager's minimum for periodic work. */
        private const val INTERVAL_HOURS = 24L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CacheCleanupWorker>(
                repeatInterval = INTERVAL_HOURS,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
