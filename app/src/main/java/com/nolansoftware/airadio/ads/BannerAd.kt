// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import kotlin.math.max

/**
 * Adaptive banner ad cell. Stable key prevents Pager churn.
 * Uses applicationContext (not Activity) to avoid leaks.
 * AdMob 22.6.0 + UMP 3.2.0: consent state is auto-applied (non-personalized
 * ads when canRequestAds() is false), so no manual `npa` extras are needed.
 */
@Composable
fun BannerAd(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext  // SAFE: never Activity
    val widthDp = LocalConfiguration.current.screenWidthDp
    val adView = remember(widthDp) {
        try {
            AdView(context).apply {
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, max(320, widthDp)))
                adUnitId = AdMobConfig.BANNER_UNIT_ID
                adListener = object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        // Silent fail — empty Box renders instead of crash.
                    }
                }
                loadAd(AdRequest.Builder().build())
            }
        } catch (e: Throwable) {
            // AdView init can throw on broken WebView environments where the
            // underlying Chrome Monochrome WebView can't resolve androidx.window
            // extensions. Render nothing instead of crashing the screen.
            android.util.Log.w("BannerAd", "AdView init failed; rendering empty", e)
            null
        }
    }
    if (adView == null) return
    DisposableEffect(Unit) { onDispose { adView.destroy() } }
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { adView }
    )
}