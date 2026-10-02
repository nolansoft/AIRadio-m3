// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.api

import java.io.IOException

/**
 * Tries a suspend [action] against each configured [RadioBrowserApi] in
 * order. On a network-level [IOException] it advances to the next region —
 * radio-browser publishes regional mirrors (`de1`, `nl1`, `at1`, ...) and
 * their reachability differs by network, so the cold-start sync should not
 * be wedged to whichever region we hardcoded.
 *
 * On any other exception (HTTP error, JSON parse failure, Room write
 * failure) the exception propagates immediately — switching regions will
 * not fix those, and silently falling through would hide the real error
 * from the user.
 *
 * When an attempt succeeds, [onSuccess] is invoked with the index of the
 * working region so the caller can persist it as the preferred region for
 * subsequent syncs. When every region fails, the last [IOException] is
 * rethrown so the banner surfaces the most recent network state rather
 * than the first region's error.
 *
 * [execute] accepts a [startIndex] for round-robin scheduling: the
 * caller passes `(lastSuccessfulIndex + 1) % size` so each cold-start
 * sync starts at a different mirror, spreading request load across the
 * fleet and avoiding always hitting index 0 first.
 */
class RegionFailoverSyncExecutor(
    private val apis: List<RadioBrowserApi>,
    private val onSuccess: (Int) -> Unit = {},
) {
    init {
        require(apis.isNotEmpty()) { "RegionFailoverSyncExecutor requires at least one API" }
    }

    suspend fun <T> execute(
        startIndex: Int = 0,
        action: suspend (RadioBrowserApi) -> T,
    ): T {
        val n = apis.size
        // Kotlin's `%` for a positive divisor always returns a non-negative
        // result, so this single mod is enough to handle negative inputs
        // like -1 (which becomes n-1). Doubly-defensive `((x % n) + n) % n`
        // is unnecessary.
        val start = ((startIndex % n) + n) % n
        var lastNetworkError: IOException? = null
        for (offset in 0 until n) {
            val idx = (start + offset) % n
            try {
                val result = action(apis[idx])
                onSuccess(idx)
                return result
            } catch (e: IOException) {
                lastNetworkError = e
            }
        }
        throw lastNetworkError ?: IOException("No API regions configured")
    }
}
