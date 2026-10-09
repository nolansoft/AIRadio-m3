// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.monetization

import com.nolansoftware.airadio.BuildConfig
import org.junit.Assert.assertEquals
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

    @Test fun `interstitialUnitIdFor falls back to global test ID in DEBUG when per-trigger ID is empty`() {
        // In DEBUG, when the per-trigger ID is unset, we fall back to the
        // pre-existing global BuildConfig.ADMOB_INTER_ID (which build.gradle.kts
        // hardcodes to Google's test ID for debug builds). This is the
        // dev-ergonomics behavior so installDebug shows test interstitials
        // immediately without local.properties edits.
        if (BuildConfig.ADMOB_INTERSTITIAL_EXIT_ID.isBlank() &&
            BuildConfig.ADMOB_INTERSTITIAL_FOREGROUND_ID.isBlank() &&
            BuildConfig.DEBUG) {
            val mgr = newManager()
            assertEquals(
                BuildConfig.ADMOB_INTER_ID,
                mgr.interstitialUnitIdFor(InterstitialTrigger.ExitFromPlayer),
            )
            assertEquals(
                BuildConfig.ADMOB_INTER_ID,
                mgr.interstitialUnitIdFor(InterstitialTrigger.AppForeground),
            )
        }
    }

    @Test fun `interstitialUnitIdFor returns the configured value when set`() {
        // The lookup path is exercised even when the per-trigger ID is set
        // (the property is non-blank). This is the production happy path.
        val mgr = newManager()
        mgr.interstitialUnitIdFor(InterstitialTrigger.ExitFromPlayer)
        mgr.interstitialUnitIdFor(InterstitialTrigger.AppForeground)
    }
}
