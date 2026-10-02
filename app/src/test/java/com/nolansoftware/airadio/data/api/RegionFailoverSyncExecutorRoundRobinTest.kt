// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Round-robin behavior for the region failover executor.
 *
 * The user wanted: "随机排序或轮询使用" — rather than always start the
 * cold-start sync against `de1` and only fall back on failure, rotate
 * the starting index across launches so a temporarily-degraded region
 * doesn't drag out every cold start, and so the request load spreads
 * across mirrors instead of hammering index 0.
 */
class RegionFailoverSyncExecutorRoundRobinTest {

    /**
     * Records the order in which each fake's `getStations` is invoked.
     * Always throws if asked, so each region surfaces an IOException
     * unless it's the one explicitly marked as `successAt`.
     */
    private class OrderedApi(
        private val id: String,
        private val success: Boolean = false,
        val log: MutableList<String>,
    ) : RadioBrowserApi {
        override suspend fun getStations(limit: Int, order: String, reverse: Boolean): List<com.nolansoftware.airadio.data.api.model.ApiStation> {
            log.add(id)
            if (success) return emptyList()
            throw IOException("$id unreachable")
        }
        override suspend fun searchStations(
            name: String?, country: String?, language: String?, tag: String?,
            offset: Int, limit: Int, order: String, reverse: Boolean,
        ): List<com.nolansoftware.airadio.data.api.model.ApiStation> = throw UnsupportedOperationException()
        override suspend fun getCountries(): List<com.nolansoftware.airadio.data.api.model.ApiCountry> = throw UnsupportedOperationException()
        override suspend fun getLanguages(): List<com.nolansoftware.airadio.data.api.model.ApiLanguage> = throw UnsupportedOperationException()
        override suspend fun getTags(): List<com.nolansoftware.airadio.data.api.model.ApiTag> = throw UnsupportedOperationException()
    }

    @Test
    fun execute_startsAtGivenIndex_andWrapsAround() = runBlocking {
        val log = mutableListOf<String>()
        // successAt=2 → the call to apis[2] succeeds, all earlier ones fail.
        val apis = listOf(
            OrderedApi("a", success = false, log = log),
            OrderedApi("b", success = false, log = log),
            OrderedApi("c", success = true,  log = log),
        )
        val executor = RegionFailoverSyncExecutor(apis)

        executor.execute(startIndex = 2) { it.getStations() }

        assertEquals(listOf("c"), log)
    }

    @Test
    fun execute_rotatesThroughAllApis_whenStartIndexIsInTheMiddle() = runBlocking {
        val log = mutableListOf<String>()
        // All four apis fail; executor must try them in wrap-around order
        // starting from index 1: 1, 2, 3, 0.
        val apis = listOf(
            OrderedApi("a", success = false, log = log),
            OrderedApi("b", success = false, log = log),
            OrderedApi("c", success = false, log = log),
            OrderedApi("d", success = false, log = log),
        )
        val executor = RegionFailoverSyncExecutor(apis)

        try {
            executor.execute(startIndex = 1) { it.getStations() }
            fail("expected IOException")
        } catch (e: IOException) {
            // last attempted is index 0 (wrapped around), which is "a"
            assertEquals("a unreachable", e.message)
        }
        assertEquals(listOf("b", "c", "d", "a"), log)
    }

    @Test
    fun execute_defaultStartIndexIsZero_preservingExistingBehavior() = runBlocking {
        val log = mutableListOf<String>()
        val apis = listOf(
            OrderedApi("a", success = true, log = log),
            OrderedApi("b", success = false, log = log),
        )
        val executor = RegionFailoverSyncExecutor(apis)

        executor.execute { it.getStations() }

        assertEquals(listOf("a"), log)
    }

    @Test
    fun execute_normalizesNegativeStartIndex() = runBlocking {
        val log = mutableListOf<String>()
        val apis = listOf(
            OrderedApi("a", success = false, log = log),
            OrderedApi("b", success = true,  log = log),
        )
        val executor = RegionFailoverSyncExecutor(apis)

        // -1 modulo 2 in Kotlin yields 1 (Kotlin's `%` returns a non-negative
        // result for positive divisor), so we must verify our explicit
        // normalization gives the same answer regardless of caller intent.
        executor.execute(startIndex = -1) { it.getStations() }

        assertEquals(listOf("b"), log)
    }

    @Test
    fun execute_normalizesStartIndexBeyondListSize() = runBlocking {
        val log = mutableListOf<String>()
        val apis = listOf(
            OrderedApi("a", success = false, log = log),
            OrderedApi("b", success = false, log = log),
            OrderedApi("c", success = true,  log = log),
        )
        val executor = RegionFailoverSyncExecutor(apis)

        // startIndex = 5 with size 3 should wrap to 5 % 3 = 2 → start at "c"
        executor.execute(startIndex = 5) { it.getStations() }

        assertEquals(listOf("c"), log)
    }

    @Test
    fun execute_invokesActionWithSameApiInstance_thatItIsTrying() = runBlocking {
        val log = mutableListOf<String>()
        val apis = listOf(
            OrderedApi("a", success = false, log = log),
            OrderedApi("b", success = true,  log = log),
        )
        val executor = RegionFailoverSyncExecutor(apis)
        val seen = mutableListOf<RadioBrowserApi>()

        executor.execute(startIndex = 1) { api ->
            seen.add(api)
            api.getStations()
        }

        assertEquals(1, seen.size)
        assertSame("executor must pass the actual apis[1] instance", apis[1], seen[0])
    }
}
