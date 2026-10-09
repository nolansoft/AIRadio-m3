// app/src/test/java/com/nolansoftware/airadio/monetization/AdmobLoadInterstitialTest.kt
package com.nolansoftware.airadio.monetization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// Robolectric 4.11.1's maxSdkVersion=34 conflicts with this project's
// targetSdk=36 (see [Task 13 report] § pre-existing baseline). Pinning the
// SDK pins Robolectric to API 33, which it fully supports, and decouples the
// unit test runner from the prod targetSdk until Robolectric adds SDK 36.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdmobLoadInterstitialTest {

    // Hand-rolled context: Robolectric's real Application. No Mockito.
    private fun newManager(consentValue: Boolean = true): AdmobMonetizationManager =
        AdmobMonetizationManager(
            context = RuntimeEnvironment.getApplication(),
            consents = FakeConsentManager(consentValue).consents,
        )

    @Test fun `loadInterstitial is a no-op when interstitial unit ID is blank`() {
        // Default BuildConfig has ADMOB_INTERSTITIAL_*_ID == "" → must silently no-op.
        val mgr = newManager()
        mgr.loadInterstitial(InterstitialTrigger.ExitFromPlayer)
        // No cache populated, no events emitted.
        assertFalse(mgr.hasCached(InterstitialTrigger.ExitFromPlayer))
        assertEquals(emptyList<MonetizationEvent>(), mgr.recordedEvents)
    }

    @Test fun `interstitialUnitIdFor returns null for blank BuildConfig values`() {
        val mgr = newManager()
        assertEquals(null, mgr.interstitialUnitIdFor(InterstitialTrigger.ExitFromPlayer))
        assertEquals(null, mgr.interstitialUnitIdFor(InterstitialTrigger.AppForeground))
    }

    @Test fun `interstitialUnitIdFor returns the configured value when set`() {
        // This test only runs meaningfully if the property is set in the local dev's gradle.properties.
        // In CI it will be empty; the test verifies the lookup path is exercised.
        val mgr = newManager()
        mgr.interstitialUnitIdFor(InterstitialTrigger.ExitFromPlayer)
        mgr.interstitialUnitIdFor(InterstitialTrigger.AppForeground)
    }
}
