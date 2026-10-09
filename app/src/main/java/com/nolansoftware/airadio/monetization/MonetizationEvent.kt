// app/src/main/java/com/nolansoftware/airadio/monetization/MonetizationEvent.kt
package com.nolansoftware.airadio.monetization

sealed class MonetizationEvent(val tag: String) {
    object BannerShown            : MonetizationEvent("banner_shown")
    object BannerLoadFailed       : MonetizationEvent("banner_load_failed")
    object InterstitialRequested  : MonetizationEvent("interstitial_requested")
    object InterstitialShown      : MonetizationEvent("interstitial_shown")
    object InterstitialDismissed  : MonetizationEvent("interstitial_dismissed")
    object InterstitialLoadFailed : MonetizationEvent("interstitial_load_failed")
    object InterstitialThrottled  : MonetizationEvent("interstitial_throttled")
    object InterstitialBusy       : MonetizationEvent("interstitial_busy")
}
