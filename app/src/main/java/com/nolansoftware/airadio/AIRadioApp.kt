// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.nolansoftware.airadio.consent.ConsentManager
import com.nolansoftware.airadio.worker.CacheCleanupWorker
import com.nolansoftware.airadio.worker.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AIRadioApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var consentManager: ConsentManager

    override fun onCreate() {
        super.onCreate()
        // Bug #1 root-cause investigation: log the device's network state at
        // startup. If SyncWorker.doWork never logs (next line below) and
        // this says `hasInternet=false`, the WorkManager constraint
        // `NetworkType.CONNECTED` is the cause and we know to focus on the
        // emulator's network setup rather than app code.
        logNetworkStateForSyncDiagnostics()
        // Sync is a global concern — schedule it on every cold start, regardless of
        // which screen the user opens first. HomeViewModel no longer calls this.
        SyncWorker.schedule(this)
        // CacheCleanupWorker bounds the paged_station_cache table size — the
        // paging layer writes rows on every scroll, so without periodic
        // cleanup the table grows monotonically across browsing history.
        CacheCleanupWorker.schedule(this)
        consentManager.initialize(this)
    }

    /**
     * Logs the device's network state at app startup so that if
     * SyncWorker.doWork never fires we can tell whether the
     * `NetworkType.CONNECTED` WorkManager constraint is the blocker.
     *
     * `NET_CAPABILITY_VALIDATED` is the stronger signal: a captive-portal
     * Wi-Fi passes `NET_CAPABILITY_INTERNET` but not `VALIDATED`, and
     * would still block the sync worker. Read both.
     */
    private fun logNetworkStateForSyncDiagnostics() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.w(TAG_NET, "startup: no ConnectivityManager service")
            return
        }
        val network = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(network)
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val hasValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        Log.i(
            TAG_NET,
            "startup: network=$network hasInternet=$hasInternet validated=$hasValidated"
        )
    }

    private companion object {
        private const val TAG_NET = "AIRadioApp"
    }

    override fun getWorkManagerConfiguration(): Configuration =
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(300)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
}