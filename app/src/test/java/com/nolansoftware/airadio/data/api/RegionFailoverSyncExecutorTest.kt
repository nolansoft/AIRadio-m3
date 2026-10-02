// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.api

import com.nolansoftware.airadio.data.api.model.ApiCountry
import com.nolansoftware.airadio.data.api.model.ApiLanguage
import com.nolansoftware.airadio.data.api.model.ApiStation
import com.nolansoftware.airadio.data.api.model.ApiTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Region failover logic for the Radio Browser API.
 *
 * The radio-browser project publishes regional mirrors (de1, nl1, at1, ...)
 * whose availability varies by network — on a CN ISP or captive portal, one
 * region may be reachable while another is not. This executor tries each
 * configured API in order; on a network-level [IOException] it advances to
 * the next region; on any other exception (HTTP error, parse failure) it
 * propagates immediately because swapping the region won't fix those.
 *
 * On success it reports which index worked so the caller can persist it
 * as the preferred region for subsequent syncs.
 */
class RegionFailoverSyncExecutorTest {

    /** Minimal fake — only [getStations] is meaningful for these tests. */
    private class FakeApi(
        private val stationsResult: Result<List<ApiStation>> = Result.success(emptyList())
    ) : RadioBrowserApi {
        var callCount = 0
            private set
        override suspend fun getStations(limit: Int, order: String, reverse: Boolean): List<ApiStation> {
            callCount++
            return stationsResult.getOrThrow()
        }
        override suspend fun searchStations(
            name: String?, country: String?, language: String?, tag: String?,
            offset: Int, limit: Int, order: String, reverse: Boolean
        ): List<ApiStation> = throw UnsupportedOperationException()
        override suspend fun getCountries(): List<ApiCountry> = throw UnsupportedOperationException()
        override suspend fun getLanguages(): List<ApiLanguage> = throw UnsupportedOperationException()
        override suspend fun getTags(): List<ApiTag> = throw UnsupportedOperationException()
    }

    @Test
    fun execute_returnsActionResult_andCallsOnSuccessWithZero_whenFirstApiSucceeds() = runBlocking {
        val api0 = FakeApi(stationsResult = Result.success(emptyList()))
        val api1 = FakeApi()
        val seenIndices = mutableListOf<Int>()
        val executor = RegionFailoverSyncExecutor(listOf(api0, api1)) { idx ->
            seenIndices.add(idx)
        }

        val result = executor.execute { it.getStations() }

        assertEquals(emptyList<ApiStation>(), result)
        assertEquals(listOf(0), seenIndices)
        assertEquals(1, api0.callCount)
        assertEquals(0, api1.callCount)
    }

    @Test
    fun execute_fallsBackToNextApi_whenFirstThrowsIOException() = runBlocking {
        val api0 = FakeApi(stationsResult = Result.failure(IOException("network unreachable")))
        val api1 = FakeApi(stationsResult = Result.success(emptyList()))
        val seenIndices = mutableListOf<Int>()
        val executor = RegionFailoverSyncExecutor(listOf(api0, api1)) { idx ->
            seenIndices.add(idx)
        }

        val result = executor.execute { it.getStations() }

        assertEquals(emptyList<ApiStation>(), result)
        assertEquals(listOf(1), seenIndices)
        assertEquals(1, api0.callCount)
        assertEquals(1, api1.callCount)
    }

    @Test
    fun execute_propagatesNonIOExceptionImmediately_withoutTryingNextRegion() = runBlocking {
        // A non-network failure (e.g. a JSON parse error) won't be fixed by
        // switching regions — fail fast and surface the real problem.
        val api0 = FakeApi(stationsResult = Result.failure(IllegalStateException("malformed JSON")))
        val api1 = FakeApi()
        val executor = RegionFailoverSyncExecutor(listOf(api0, api1)) { /* noop */ }

        try {
            executor.execute { it.getStations() }
            fail("expected IllegalStateException to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("malformed JSON", e.message)
        }
        assertEquals(1, api0.callCount)
        assertEquals("next region must NOT be tried on non-IOException", 0, api1.callCount)
    }

    @Test
    fun execute_throwsLastIOException_whenAllRegionsFail() = runBlocking {
        val api0 = FakeApi(stationsResult = Result.failure(IOException("region 0 down")))
        val api1 = FakeApi(stationsResult = Result.failure(IOException("region 1 down")))
        val seenIndices = mutableListOf<Int>()
        val executor = RegionFailoverSyncExecutor(listOf(api0, api1)) { idx ->
            seenIndices.add(idx)
        }

        try {
            executor.execute { it.getStations() }
            fail("expected IOException to surface")
        } catch (e: IOException) {
            // The executor surfaces the LAST failure so the user sees the
            // most recent network state rather than the first region's error.
            assertEquals("region 1 down", e.message)
        }
        assertTrue("onSuccess must not be called when every region failed", seenIndices.isEmpty())
        assertEquals(1, api0.callCount)
        assertEquals(1, api1.callCount)
    }

    @Test
    fun execute_callsOnSuccessWithCorrectIndex_whenLaterApiSucceeds() = runBlocking {
        val api0 = FakeApi(stationsResult = Result.failure(IOException("region 0 down")))
        val api1 = FakeApi(stationsResult = Result.failure(IOException("region 1 down")))
        val api2 = FakeApi(stationsResult = Result.success(emptyList()))
        val seenIndices = mutableListOf<Int>()
        val executor = RegionFailoverSyncExecutor(listOf(api0, api1, api2)) { idx ->
            seenIndices.add(idx)
        }

        executor.execute { it.getStations() }

        assertEquals(listOf(2), seenIndices)
        assertEquals(1, api0.callCount)
        assertEquals(1, api1.callCount)
        assertEquals(1, api2.callCount)
    }

    @Test
    fun execute_passesTheSameApiInstanceToAction_thatItIsTrying() = runBlocking {
        val api0 = FakeApi()
        val api1 = FakeApi()
        val executor = RegionFailoverSyncExecutor(listOf(api0, api1)) { /* noop */ }
        val seenApis = mutableListOf<RadioBrowserApi>()

        executor.execute { api ->
            seenApis.add(api)
            api.getStations()
        }

        assertSame("first attempt must use api[0]", api0, seenApis[0])
        assertEquals(1, seenApis.size)
    }
}
