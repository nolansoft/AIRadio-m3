// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.monetization

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * Central facade for the ad-monetization subsystem.
 *
 * Implementations must:
 *  - Never throw from any method — every failure mode is logged via
 *    [recordEvent] (with a matching [MonetizationEvent]) and returns a safe
 *    default (e.g. an empty `@Composable`, `false` for the show path).
 *  - Treat `isAdsEnabled` as the single gate: when false, all ads are silent.
 *  - Behave identically across the process lifetime — no re-binding, no
 *    re-construction between Activity restarts. Hilt's `@Singleton` already
 *    enforces this.
 *
 * The full ad policy (frequency caps, per-surface unit IDs, session counters,
 * WebView-incompatible fallbacks) lives in `AdmobMonetizationManager`; this
 * interface deliberately exposes only the verbs the rest of the app needs.
 */
interface MonetizationManager {
    /**
     * True iff the user has either granted consent (or it wasn't required)
     * AND the device's WebView is AdMob-compatible. False during the
     * pre-consent Required state, on OfflineFallback, on
     * WebViewIncompatible devices, or before the first UMP callback fires.
     */
    val isAdsEnabled: StateFlow<Boolean>

    /**
     * Compose-only banner ad slot for the given [surfaceId].
     * Implementations decide whether to render or render an empty Box
     * based on `isAdsEnabled` and the surface's resolved ad-unit ID.
     *
     * The Kotlin compiler disallows default values on abstract `@Composable`
     * functions, so callers must pass `Modifier` explicitly. Most callers
     * want `Modifier.fillMaxWidth()` or similar — see the surface-level
     * wrapper Composables in `ui/screens/`.
     */
    @Composable
    fun BannerAd(surfaceId: SurfaceId, modifier: Modifier)

    /**
     * Kick off an interstitial ad load for the given [trigger]. Safe to
     * call repeatedly; implementations debounce internally per
     * `AdMobConfig.FREQ_*_WINDOW_MS`. Never throws.
     */
    fun loadInterstitial(trigger: InterstitialTrigger)

    /**
     * Synchronous (NOT suspend). Returns true iff an ad was actually shown.
     * All refusal reasons are silent unless they emit a [MonetizationEvent]
     * (see `InterstitialThrottled`, `InterstitialBusy`).
     *
     * Callers are typically Composable `onDispose` callbacks or
     * `Lifecycle.Event.ON_START` observers — both non-suspend contexts.
     */
    fun showInterstitialIfReady(trigger: InterstitialTrigger): Boolean

    /**
     * Append a monetization event to the in-memory ring buffer
     * (`AdmobMonetizationManager.recordedEvents`) and emit a log line.
     * Used by Banner/Interstitial implementations and external callers
     * that want to share the same event log.
     */
    fun recordEvent(event: MonetizationEvent)
}
