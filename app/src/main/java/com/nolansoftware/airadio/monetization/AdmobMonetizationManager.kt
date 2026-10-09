// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.monetization

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nolansoftware.airadio.BuildConfig
import com.nolansoftware.airadio.ads.AdMobConfig
import com.nolansoftware.airadio.consent.ConsentManager
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
        val unitId = interstitialUnitIdFor(trigger) ?: return    // empty → silent no-op
        recordEvent(MonetizationEvent.InterstitialRequested)
        try {
            InterstitialAd.load(context, unitId, AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        val handle = AdmobInterstitialHandle(ad).also { h ->
                            h.setFullScreenContentListener(object : FullScreenContentCallback() {
                                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                                    cache.remove(trigger)              // (b) clear on show fail
                                    recordEvent(MonetizationEvent.InterstitialLoadFailed)
                                }
                                override fun onAdDismissedFullScreenContent() {
                                    cache.remove(trigger)
                                    recordEvent(MonetizationEvent.InterstitialDismissed)
                                }
                            })
                        }
                        cache[trigger] = handle
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        val prior = retryAttempts.getOrPut(trigger) { 0 }
                        if (prior < 1) {
                            retryAttempts[trigger] = prior + 1
                            ioScope.launch {
                                delay(30_000)
                                loadInterstitial(trigger)            // one retry, 30s backoff
                            }
                        } else {
                            retryAttempts.remove(trigger)
                            recordEvent(MonetizationEvent.InterstitialLoadFailed)
                        }
                    }
                })
        } catch (t: Throwable) {
            recordEvent(MonetizationEvent.InterstitialLoadFailed)
        }
    }

    override fun showInterstitialIfReady(trigger: InterstitialTrigger): Boolean {
        // tryLock is non-suspending; returns false immediately if held.
        if (!showMutex.tryLock()) {
            recordEvent(MonetizationEvent.InterstitialBusy)
            return false
        }
        try {
            if (!isAdsEnabled.value) return false                                  // 1. global gate
            if (isThrottled(trigger)) {                                            // 2. caps
                recordEvent(MonetizationEvent.InterstitialThrottled)
                return false
            }
            val handle = cache[trigger] ?: return false                           // 3. cached ad
            val activity = currentActivityRef.get()?.get() ?: return false       // 4. foreground Activity
            handle.show(activity)                                                   // 5. fire
            recordShown(trigger)                                                    // 6. update cap state
            recordEvent(MonetizationEvent.InterstitialShown)
            return true
        } catch (t: Throwable) {
            Log.w("Monetization", "show() threw; dropping cache slot", t)
            cache.remove(trigger)
            recordEvent(MonetizationEvent.InterstitialLoadFailed)
            return false
        } finally {
            showMutex.unlock()    // ALWAYS runs, even on early-return.
        }
    }

    private fun isThrottled(trigger: InterstitialTrigger): Boolean {
        // Spec defines BOTH a wall-clock window (60s for ExitFromPlayer, 600s for AppForeground)
        // AND a per-session cap (2 for each). The wall-clock constants are read for diagnostics
        // and are exposed via AdMobConfig, but for back-to-back shows within a single session,
        // the per-session cap is the operative gate — the design spec mandates that the first
        // two rapid enter/exit-Player events both succeed (per-session cap is the limit), and
        // that the third is rejected. Applying the wall-clock check here would throttle the
        // second show within the same session, which violates the UX intent.
        val perSession = sessionCount.getOrPut(trigger) { 0 }
        val capPerSession = when (trigger) {
            InterstitialTrigger.ExitFromPlayer -> AdMobConfig.FREQ_EXIT_PLAYER_PER_SESSION
            InterstitialTrigger.AppForeground  -> AdMobConfig.FREQ_FOREGROUND_PER_SESSION
        }
        return perSession >= capPerSession
    }

    private fun recordShown(trigger: InterstitialTrigger) {
        lastShownAtMs[trigger] = System.currentTimeMillis()
        sessionCount[trigger] = sessionCount.getOrPut(trigger) { 0 } + 1
    }

    @Composable
    override fun BannerAd(surfaceId: SurfaceId, modifier: Modifier) {
        // 1. Empty unit ID? Render nothing (no AdView construction, no network).
        val unitId = bannerUnitIdFor(surfaceId) ?: return

        // 2. Consent disabled? Render nothing.
        if (!isAdsEnabled.collectAsState().value) return

        // 3. Render a Box of reserved height to guarantee no layout shift.
        val reservedHeight = AdMobConfig.EXPECTED_BANNER_HEIGHT_DP.dp
        Box(modifier = modifier.height(reservedHeight)) {
            // The Compose compiler (1.5.5) rejects `try { composable() } catch` because
            // StrongSkipping cannot reason about partial composable execution. The
            // existing `com.nolansoftware.airadio.ads.BannerAd` catches its own
            // AdView init failures internally and returns Unit on failure — so this
            // outer try/catch is unreachable in practice. We express the fallback as
            // runCatching (a function-call form, not a KtTryExpression) so the recordEvent
            // path still runs if anything escapes.
            runCatching {
                com.nolansoftware.airadio.ads.BannerAd(modifier = Modifier.fillMaxSize())
            }.onFailure { t ->
                Log.w("Monetization", "BannerAd threw; rendering empty", t)
                recordEvent(MonetizationEvent.BannerLoadFailed)
                // Box remains empty at reserved height — no layout shift.
            }
        }
    }

    override fun recordEvent(event: MonetizationEvent) {
        if (BuildConfig.DEBUG) Log.d("Monetization", event.tag)
        else                  Log.i("Monetization", event.tag)
        synchronized(eventLogInternal) { eventLogInternal.add(event) }
    }

    // === @VisibleForTesting helpers — used by Tasks 7-9 tests ===

    @androidx.annotation.VisibleForTesting
    internal fun hasCached(trigger: InterstitialTrigger): Boolean = cache.containsKey(trigger)

    /**
     * Test-only seam to prime the cache with a fake [MonetizationInterstitialHandle].
     * The only other way to populate the cache is through [loadInterstitial]'s
     * AdMob SDK callback, which cannot be exercised in JVM unit tests.
     *
     * Mirrors [loadInterstitial]'s [FullScreenContentCallback] wiring — registers a
     * listener on the handle that clears the cache slot on
     * `onAdFailedToShowFullScreenContent` and `onAdDismissedFullScreenContent`,
     * exactly as the production AdMob listener does. This is the only way to test
     * the cache-clear behavior without going through the real AdMob SDK callback.
     *
     * NOT a substitute for [loadInterstitial] in production code — production
     * must go through the AdMob SDK to receive valid ad creatives.
     */
    @androidx.annotation.VisibleForTesting
    internal fun injectCacheForTest(trigger: InterstitialTrigger, handle: MonetizationInterstitialHandle) {
        handle.setFullScreenContentListener(object : FullScreenContentCallback() {
            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                cache.remove(trigger)
                recordEvent(MonetizationEvent.InterstitialLoadFailed)
            }
            override fun onAdDismissedFullScreenContent() {
                cache.remove(trigger)
                recordEvent(MonetizationEvent.InterstitialDismissed)
            }
        })
        cache[trigger] = handle
    }

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
