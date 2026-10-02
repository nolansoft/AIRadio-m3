// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.api

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which Radio Browser region last succeeded so the cold-start sync
 * can prefer a known-working region instead of always starting at index 0.
 *
 * Backed by [android.content.SharedPreferences] so the choice survives
 * process death — a network where de1 was unreachable yesterday is likely
 * the same tomorrow (corporate firewall, captive portal, ISP geo-block).
 */
interface RegionStore {
    var currentIndex: Int
}

@Singleton
class SharedPrefsRegionStore @Inject constructor(
    @ApplicationContext context: Context,
) : RegionStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var currentIndex: Int
        get() = prefs.getInt(KEY_REGION_INDEX, 0)
        set(value) {
            prefs.edit().putInt(KEY_REGION_INDEX, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "radio_browser_prefs"
        const val KEY_REGION_INDEX = "region_index"
    }
}
