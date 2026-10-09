package com.nolansoftware.airadio.monetization

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NoOpMonetizationManagerTest {

    @Test fun `isAdsEnabled flow is permanently false`() = runTest {
        val mgr = NoOpMonetizationManager()
        assertEquals(false, mgr.isAdsEnabled.first())
    }

    @Test fun `showInterstitialIfReady always returns false`() {
        val mgr = NoOpMonetizationManager()
        assertFalse(mgr.showInterstitialIfReady(InterstitialTrigger.ExitFromPlayer))
        assertFalse(mgr.showInterstitialIfReady(InterstitialTrigger.AppForeground))
    }

    @Test fun `loadInterstitial and recordEvent do not throw`() {
        val mgr = NoOpMonetizationManager()
        mgr.loadInterstitial(InterstitialTrigger.ExitFromPlayer)
        mgr.recordEvent(MonetizationEvent.BannerShown)    // must not throw, must not NPE
    }
}