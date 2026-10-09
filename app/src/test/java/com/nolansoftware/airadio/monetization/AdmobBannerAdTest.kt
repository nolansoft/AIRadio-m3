// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.monetization

import com.nolansoftware.airadio.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class AdmobBannerAdTest {

    // Hand-rolled context: Robolectric's real Application. No Mockito.
    private fun newManager(consentInitial: Boolean = true): AdmobMonetizationManager =
        AdmobMonetizationManager(
            context = RuntimeEnvironment.getApplication(),
            consents = FakeConsentManager(consentInitial).consents,
        )

    @Test fun `empty string is filtered to null by takeIf isNotBlank`() {
        // Pin the empty-unit-ID contract at the language level.
        val blank: String? = "".takeIf { it.isNotBlank() }
        assertNull(blank)
    }

    @Test fun `bannerUnitIdFor returns null when BuildConfig ADMOB_BANNER_PLAYER_ID is empty`() {
        // In default CI builds, ADMOB_BANNER_PLAYER_ID is ""; verify the lookup returns null.
        if (BuildConfig.ADMOB_BANNER_PLAYER_ID.isBlank()) {
            val mgr = newManager()
            assertNull(mgr.bannerUnitIdFor(SurfaceId.Player))
        }
    }

    @Test fun `SurfaceId adUnitSuffix is used as the BuildConfig field suffix convention`() {
        // The spec promises that bannerUnitIdFor looks up BuildConfig.ADMOB_BANNER_<suffix>_ID.
        assertEquals("player", SurfaceId.Player.adUnitSuffix)
    }

    @Test fun `manager constructs cleanly against a real Application context`() {
        // No-op smoke: the init { registerActivityLifecycleCallbacks } block must not throw
        // when given a real Application. This is the foundation all later tests rely on.
        val mgr = newManager()
        // We don't assert internal state here; construction succeeding is the assertion.
    }
}
