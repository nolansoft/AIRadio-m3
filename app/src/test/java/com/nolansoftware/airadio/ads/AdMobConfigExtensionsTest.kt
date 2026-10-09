// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ads

import org.junit.Assert.assertEquals
import org.junit.Test

class AdMobConfigExtensionsTest {

    @Test fun `expected banner height is 50dp for portrait`() {
        assertEquals(50, AdMobConfig.EXPECTED_BANNER_HEIGHT_DP)
    }

    @Test fun `ExitFromPlayer caps are 60s and 2 per session`() {
        assertEquals(60_000L, AdMobConfig.FREQ_EXIT_PLAYER_WINDOW_MS)
        assertEquals(2,        AdMobConfig.FREQ_EXIT_PLAYER_PER_SESSION)
    }

    @Test fun `AppForeground caps are 10min and 2 per session`() {
        assertEquals(600_000L, AdMobConfig.FREQ_FOREGROUND_WINDOW_MS)
        assertEquals(2,        AdMobConfig.FREQ_FOREGROUND_PER_SESSION)
    }
}