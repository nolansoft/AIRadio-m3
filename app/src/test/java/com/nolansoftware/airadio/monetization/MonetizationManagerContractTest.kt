// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.monetization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MonetizationManagerContractTest {

    // --- Type-level contract only; behavioral tests in later tasks ---

    @Test fun `InterstitialBusy and InterstitialThrottled are distinct events`() {
        // This test pins the spec's bucket-split decision.
        assertNotEquals(
            MonetizationEvent.InterstitialBusy.tag,
            MonetizationEvent.InterstitialThrottled.tag,
        )
    }

    @Test fun `expected frequency caps are 60s 2-session Exit and 600s 2-session Foreground`() {
        // Re-export cap constants here from AdMobConfig to fail loudly if anyone
        // accidentally loosens the budget.
        assertEquals(60_000L, com.nolansoftware.airadio.ads.AdMobConfig.FREQ_EXIT_PLAYER_WINDOW_MS)
        assertEquals(2,        com.nolansoftware.airadio.ads.AdMobConfig.FREQ_EXIT_PLAYER_PER_SESSION)
        assertEquals(600_000L, com.nolansoftware.airadio.ads.AdMobConfig.FREQ_FOREGROUND_WINDOW_MS)
        assertEquals(2,        com.nolansoftware.airadio.ads.AdMobConfig.FREQ_FOREGROUND_PER_SESSION)
    }
}
