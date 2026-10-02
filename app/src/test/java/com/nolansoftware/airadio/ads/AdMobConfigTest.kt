// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ads

import com.nolansoftware.airadio.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smoke tests for [AdMobConfig] constants. These guard the banner-placement
 * contract consumed by StationListScreen and SearchScreen — a stray edit to
 * the interval / threshold / position values silently degrades UX (too few
 * ads = lost revenue, too many = UX noise). Constants are tested against
 * their M1 §Lane-C pinned values.
 */
class AdMobConfigTest {

    @Test
    fun bannerInterval_isPinnedToTwelve() {
        assertEquals(12, AdMobConfig.BANNER_INTERVAL)
    }

    @Test
    fun bannerSearchThreshold_isPinnedToTen() {
        assertEquals(10, AdMobConfig.BANNER_SEARCH_THRESHOLD)
    }

    @Test
    fun bannerSearchPosition_isPinnedToFive() {
        assertEquals(5, AdMobConfig.BANNER_SEARCH_POSITION)
    }

    @Test
    fun bannerUnitId_delegatesToBuildConfig() {
        // BANNER_UNIT_ID is a `val` that reads BuildConfig.ADMOB_BANNER_ID — verify
        // the wiring so a future refactor that decouples them fails loudly.
        assertEquals(BuildConfig.ADMOB_BANNER_ID, AdMobConfig.BANNER_UNIT_ID)
    }

    @Test
    fun bannerUnitId_isNonEmpty() {
        // Defense-in-depth: prevents shipping a build with an accidentally blank
        // banner ID, which would silently render empty ad slots in production.
        assert(AdMobConfig.BANNER_UNIT_ID.isNotEmpty()) {
            "BANNER_UNIT_ID resolved to empty string — check BuildConfig.ADMOB_BANNER_ID"
        }
    }
}