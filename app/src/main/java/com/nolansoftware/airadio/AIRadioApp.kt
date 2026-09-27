package com.nolansoftware.airadio

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.nolansoftware.airadio.worker.CacheCleanupWorker
import com.nolansoftware.airadio.worker.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AIRadioApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // Sync is a global concern — schedule it on every cold start, regardless of
        // which screen the user opens first. HomeViewModel no longer calls this.
        SyncWorker.schedule(this)
        // CacheCleanupWorker bounds the paged_station_cache table size — the
        // paging layer writes rows on every scroll, so without periodic
        // cleanup the table grows monotonically across browsing history.
        CacheCleanupWorker.schedule(this)
    }

    override fun getWorkManagerConfiguration(): Configuration =
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}