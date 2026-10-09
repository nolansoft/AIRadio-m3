// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.monetization

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nolansoftware.airadio.BuildConfig
import com.nolansoftware.airadio.ads.AdMobConfig
import com.nolansoftware.airadio.consent.ConsentManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real AdMob-backed implementation of [MonetizationManager].
 *
 * Lifecycle invariants:
 *  - The Activity reference is held only as a [WeakReference] updated by
 *    [Application.ActivityLifecycleCallbacks]. Never stored as a field.
 *  - The show path uses [Mutex.tryLock] — non-blocking on the main thread.
 *  - All AdMob exceptions are caught locally; none are rethrown.
 *
 * Tasks 6 (banner), 7 (load), 8 (consent gate), and 9 (show) fill in the
 * business methods below. This class is a skeleton: state holders, the
 * Activity ref tracker, the cache, and the unit-ID resolvers are wired so
 * those tasks can be additive changes without touching the lifecycle code.
 */
@Singleton
class AdmobMonetizationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val consents: ConsentManager,
) : MonetizationManager {

    // === State ===

    private val currentActivityRef: java.util.concurrent.atomic.AtomicReference<WeakReference<Activity>> =
        java.util.concurrent.atomic.AtomicReference(WeakReference(null))

    private val cache: ConcurrentHashMap<InterstitialTrigger, MonetizationInterstitialHandle> =
        ConcurrentHashMap()

    private val retryAttempts: ConcurrentHashMap<InterstitialTrigger, Int> =
        ConcurrentHashMap()

    private val sessionCount: ConcurrentHashMap<InterstitialTrigger, Int> =
        ConcurrentHashMap()

    private val lastShownAtMs: ConcurrentHashMap<InterstitialTrigger, Long> =
        ConcurrentHashMap()

    private val showMutex = Mutex()    // kotlinx.coroutines.sync — tryLock only, no suspending lock

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val isAdsEnabled: StateFlow<Boolean> =
        consents.canRequestAds().stateIn(ioScope, SharingStarted.Eagerly, initialValue = false)

    private val eventLogInternal: MutableList<MonetizationEvent> = mutableListOf()

    init {
        (context as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityResumed(a: Activity) { currentActivityRef.set(WeakReference(a)) }
                override fun onActivityPaused(a: Activity)     { currentActivityRef.set(WeakReference(null)) }
                override fun onActivityStopped(a: Activity)    {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                override fun onActivityDestroyed(a: Activity)  {}
            }
        )
    }

    // === Business impl — placeholder, real impl in Tasks 6/7/8/9 ===

    override fun loadInterstitial(trigger: InterstitialTrigger) {
        // Implemented in Task 7.
    }

    override fun showInterstitialIfReady(trigger: InterstitialTrigger): Boolean {
        // Implemented in Task 9.
        return false
    }

    @Composable
    override fun BannerAd(surfaceId: SurfaceId, modifier: Modifier) {
        // Implemented in Task 6.
    }

    override fun recordEvent(event: MonetizationEvent) {
        if (BuildConfig.DEBUG) Log.d("Monetization", event.tag)
        else                  Log.i("Monetization", event.tag)
        synchronized(eventLogInternal) { eventLogInternal.add(event) }
    }

    // === @VisibleForTesting helpers — used by Tasks 7-9 tests ===

    @androidx.annotation.VisibleForTesting
    internal fun hasCached(trigger: InterstitialTrigger): Boolean = cache.containsKey(trigger)

    @androidx.annotation.VisibleForTesting
    internal fun tryAcquireShowMutexForTest(): Boolean =
        if (showMutex.tryLock()) { showMutex.unlock(); true } else false

    @androidx.annotation.VisibleForTesting
    internal val recordedEvents: List<MonetizationEvent>
        get() = synchronized(eventLogInternal) { eventLogInternal.toList() }

    // === Unit-ID resolution (used by Tasks 6 and 7) ===

    internal fun bannerUnitIdFor(surfaceId: SurfaceId): String? = when (surfaceId) {
        SurfaceId.Player      -> BuildConfig.ADMOB_BANNER_PLAYER_ID.takeIf { it.isNotBlank() }
        SurfaceId.Home        -> BuildConfig.ADMOB_BANNER_HOME_ID.takeIf { it.isNotBlank() }
            ?: AdMobConfig.BANNER_UNIT_ID.takeIf { it.isNotBlank() }
        SurfaceId.Search      -> BuildConfig.ADMOB_BANNER_SEARCH_ID.takeIf { it.isNotBlank() }
            ?: AdMobConfig.BANNER_UNIT_ID.takeIf { it.isNotBlank() }
        SurfaceId.Browse      -> BuildConfig.ADMOB_BANNER_BROWSE_ID.takeIf { it.isNotBlank() }
            ?: AdMobConfig.BANNER_UNIT_ID.takeIf { it.isNotBlank() }
        SurfaceId.Favorites   -> BuildConfig.ADMOB_BANNER_FAVORITES_ID.takeIf { it.isNotBlank() }
            ?: AdMobConfig.BANNER_UNIT_ID.takeIf { it.isNotBlank() }
        SurfaceId.StationList -> BuildConfig.ADMOB_BANNER_STATIONLIST_ID.takeIf { it.isNotBlank() }
            ?: AdMobConfig.BANNER_UNIT_ID.takeIf { it.isNotBlank() }
    }

    internal fun interstitialUnitIdFor(trigger: InterstitialTrigger): String? = when (trigger) {
        InterstitialTrigger.ExitFromPlayer -> AdMobConfig.INTERSTITIAL_EXIT_ID.takeIf { it.isNotBlank() }
        InterstitialTrigger.AppForeground  -> AdMobConfig.INTERSTITIAL_FOREGROUND_ID.takeIf { it.isNotBlank() }
    }
}
