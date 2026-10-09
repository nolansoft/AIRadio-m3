// app/src/main/java/com/nolansoftware/airadio/monetization/AdmobInterstitialHandle.kt
package com.nolansoftware.airadio.monetization

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.interstitial.InterstitialAd

/**
 * Real AdMob-backed [MonetizationInterstitialHandle].
 *
 * Constructed by [AdmobMonetizationManager.loadInterstitial] when an
 * `InterstitialAd.load(...)` callback fires. Wraps the AdMob SDK type so
 * the manager never holds an `InterstitialAd` reference directly.
 */
class AdmobInterstitialHandle(private val ad: InterstitialAd) : MonetizationInterstitialHandle {

    override fun show(activity: Activity) {
        ad.show(activity)
    }

    override fun setFullScreenContentListener(listener: FullScreenContentCallback) {
        ad.fullScreenContentCallback = listener
    }

    /**
     * Convenience for callers that need to forward AdError events
     * (e.g., to clear cache on show failure). Not part of the interface
     * because test fakes don't need it.
     */
    fun onAdFailedToShow(error: AdError) {
        // The listener pattern means the manager owns the callback body;
        // this is exposed only for diagnostics if needed.
    }
}