// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ads

import com.nolansoftware.airadio.BuildConfig

object AdMobConfig {
    // === BANNER ===
    const val BANNER_INTERVAL = 12          // StationListScreen paging grid
    const val BANNER_SEARCH_THRESHOLD = 10  // SearchScreen — only if results >= this
    const val BANNER_SEARCH_POSITION = 5    // SearchScreen — render at this position
    const val EXPECTED_BANNER_HEIGHT_DP = 50 // portrait only; tablet/landscape deferred
    val BANNER_UNIT_ID: String = BuildConfig.ADMOB_BANNER_ID
    val BANNER_PLAYER_ID: String = BuildConfig.ADMOB_BANNER_PLAYER_ID

    // === INTERSTITIAL ===
    val INTERSTITIAL_EXIT_ID: String = BuildConfig.ADMOB_INTERSTITIAL_EXIT_ID
    val INTERSTITIAL_FOREGROUND_ID: String = BuildConfig.ADMOB_INTERSTITIAL_FOREGROUND_ID

    // === FREQUENCY CAPS ===
    const val FREQ_EXIT_PLAYER_WINDOW_MS = 60_000L    // 60 s
    const val FREQ_EXIT_PLAYER_PER_SESSION = 2        // per cold-start session
    const val FREQ_FOREGROUND_WINDOW_MS = 600_000L    // 10 min
    const val FREQ_FOREGROUND_PER_SESSION = 2

    // === GOOGLE OFFICIAL TEST UNIT IDS ===
    // Fill on ANY device (including emulators), unlike real units which
    // Google refuses to fill off real devices. Used by DEBUG builds
    // (BuildConfig.DEBUG) so emulator verification runs exercise the full ad
    // pipeline with fillable inventory. Must stay in sync with the literals
    // in app/build.gradle.kts (debug buildType).
    const val TEST_BANNER_UNIT_ID = "ca-app-pub-3940256099942544/9214589741"
    const val TEST_INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
}