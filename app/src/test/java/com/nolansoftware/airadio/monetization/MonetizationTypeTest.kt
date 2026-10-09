// app/src/test/java/com/nolansoftware/airadio/monetization/MonetizationTypeTest.kt
package com.nolansoftware.airadio.monetization

import org.junit.Assert.assertEquals
import org.junit.Test

class MonetizationTypeTest {

    @Test fun `SurfaceId adUnitSuffix values are stable`() {
        assertEquals("player",      SurfaceId.Player.adUnitSuffix)
        assertEquals("home",        SurfaceId.Home.adUnitSuffix)
        assertEquals("search",      SurfaceId.Search.adUnitSuffix)
        assertEquals("browse",      SurfaceId.Browse.adUnitSuffix)
        assertEquals("favorites",   SurfaceId.Favorites.adUnitSuffix)
        assertEquals("station_list", SurfaceId.StationList.adUnitSuffix)
    }

    @Test fun `InterstitialTrigger adUnitSuffix values are stable`() {
        assertEquals("exit_player", InterstitialTrigger.ExitFromPlayer.adUnitSuffix)
        assertEquals("foreground",  InterstitialTrigger.AppForeground.adUnitSuffix)
    }

    @Test fun `MonetizationEvent tags are stable for analytics consumers`() {
        assertEquals("banner_shown",             MonetizationEvent.BannerShown.tag)
        assertEquals("banner_load_failed",       MonetizationEvent.BannerLoadFailed.tag)
        assertEquals("interstitial_requested",   MonetizationEvent.InterstitialRequested.tag)
        assertEquals("interstitial_shown",       MonetizationEvent.InterstitialShown.tag)
        assertEquals("interstitial_dismissed",   MonetizationEvent.InterstitialDismissed.tag)
        assertEquals("interstitial_load_failed", MonetizationEvent.InterstitialLoadFailed.tag)
        assertEquals("interstitial_throttled",   MonetizationEvent.InterstitialThrottled.tag)
        assertEquals("interstitial_busy",        MonetizationEvent.InterstitialBusy.tag)
    }
}
