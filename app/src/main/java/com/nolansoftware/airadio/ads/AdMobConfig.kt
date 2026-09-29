// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ads

import com.nolansoftware.airadio.BuildConfig

object AdMobConfig {
    const val BANNER_INTERVAL = 12          // StationListScreen paging grid
    const val BANNER_SEARCH_THRESHOLD = 10  // SearchScreen — only if results >= this
    const val BANNER_SEARCH_POSITION = 5    // SearchScreen — render at this position
    val BANNER_UNIT_ID: String = BuildConfig.ADMOB_BANNER_ID
}