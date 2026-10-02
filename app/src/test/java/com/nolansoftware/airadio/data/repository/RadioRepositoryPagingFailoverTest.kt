// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.repository

import com.nolansoftware.airadio.data.api.RadioBrowserApi
import com.nolansoftware.airadio.data.api.RegionStore
import com.nolansoftware.airadio.data.api.model.ApiCountry
import com.nolansoftware.airadio.data.api.model.ApiLanguage
import com.nolansoftware.airadio.data.api.model.ApiStation
import com.nolansoftware.airadio.data.api.model.ApiTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * RB-004 regression guard. `RadioRepository.fetchStationsPage` is the
 * cold-start paging path for Browse > Country / Language / Tag; on every
 * page load it now runs through `fetchStationsPageWithFailover`, a
 * top-level helper that wraps `RegionFailoverSyncExecutor` with the
 * persisted `regionStore.currentIndex` as the start point and updates
 * that index on the first API that succeeds.
 *
 * The executor-level retry semantics are already covered by
 * `RegionFailoverSyncExecutorTest` and
 * `RegionFailoverSyncExecutorRoundRobinTest`. These tests focus on the
 * four contract points that distinguish "real failover" from
 * "every-page-round-robins":
 *  1. preferred API succeeds → only preferred is called
 *  2. preferred throws IOException, next succeeds → fallback is called
 *  3. all APIs throw IOException → last IOException is propagated
 *  4. preferred throws HttpException → do NOT failover (HTTP errors are
 *     not network-connection failures; swapping regions won't fix 401/403/404)
 *
 * Plus one extra check that the persisted currentIndex is consulted as
 * the executor's startIndex, so consecutive page loads rotate across
 * mirrors instead of always hammering index 0.
 */
class RadioRepositoryPagingFailoverTest {

    /** Records the order in which each fake's `searchStations` is invoked. */
    private class OrderedApi(
        private val id: String,
        private val stationsResult: Result<List<ApiStation>> = Result.success(emptyList()),
        val log: MutableList<String>,
    ) : RadioBrowserApi {
        override suspend fun getStations(limit: Int, order: String, reverse: Boolean): List<ApiStation> =
            throw UnsupportedOperationException()
        override suspend fun searchStations(
            name: String?, country: String?, language: String?, tag: String?,
            offset: Int, limit: Int, order: String, reverse: Boolean,
        ): List<ApiStation> {
            log.add(id)
            return stationsResult.getOrThrow()
        }
        override suspend fun getCountries(): List<ApiCountry> = throw UnsupportedOperationException()
        override suspend fun getLanguages(): List<ApiLanguage> = throw UnsupportedOperationException()
        override suspend fun getTags(): List<ApiTag> = throw UnsupportedOperationException()
    }

    /** In-memory RegionStore; tracks setter calls so we can assert callbacks fire. */
    private class FakeRegionStore(initialIndex: Int = 0) : RegionStore {
        override var currentIndex: Int = initialIndex
        var setCount: Int = 0
            private set
    }

    @Test
    fun preferredApiSucceeds_callsOnlyPreferred_andDoesNotOverwriteCurrentIndex() = runBlocking {
        val log = mutableListOf<String>()
        val apis = listOf(
            OrderedApi("a", stationsResult = Result.success(emptyList()), log = log),
            OrderedApi("b", stationsResult = Result.success(emptyList()), log = log),
        )
        val store = FakeRegionStore(initialIndex = 0)

        val result = fetchStationsPageWithFailover(apis, store) { it.searchStations() }

        assertEquals(emptyList<ApiStation>(), result)
        assertEquals(listOf("a"), log)
        assertEquals("index unchanged when winner == persisted index", 0, store.currentIndex)
        assertEquals("callback must NOT fire when persisted index already won", 0, store.setCount)
    }

    @Test
    fun preferredApiThrowsIoException_fallsBackToNext_andUpdatesCurrentIndex() = runBlocking {
        val log = mutableListOf<String>()
        val apis = listOf(
            OrderedApi("a", stationsResult = Result.failure(IOException("preferred down")), log = log),
            OrderedApi("b", stationsResult = Result.success(emptyList()), log = log),
        )
        val store = FakeRegionStore(initialIndex = 0)

        val result = fetchStationsPageWithFailover(apis, store) { it.searchStations() }

        assertEquals(emptyList<ApiStation>(), result)
        assertEquals(listOf("a", "b"), log)
        assertEquals("callback updates currentIndex to the winning api", 1, store.currentIndex)
    }

    @Test
    fun allApisThrowIoException_propagatesLastIoException_andDoesNotUpdateIndex() = runBlocking {
        val log = mutableListOf<String>()
        val apis = listOf(
            OrderedApi("a", stationsResult = Result.failure(IOException("a down")), log = log),
            OrderedApi("b", stationsResult = Result.failure(IOException("b down")), log = log),
            OrderedApi("c", stationsResult = Result.failure(IOException("c down")), log = log),
        )
        val store = FakeRegionStore(initialIndex = 0)

        try {
            fetchStationsPageWithFailover(apis, store) { it.searchStations() }
            fail("expected IOException")
        } catch (e: IOException) {
            // Executor surfaces the LAST failure so the user sees the most
            // recent network state, not the first region's error.
            assertEquals("c down", e.message)
        }
        assertEquals(listOf("a", "b", "c"), log)
        assertEquals("failed run must NOT overwrite the persisted currentIndex", 0, store.currentIndex)
    }

    @Test
    fun preferredApiThrowsHttpException_doesNotFailover_propagatesImmediately() = runBlocking {
        // 4xx / 5xx is not a network-connection failure. Swapping regions
        // cannot fix a 401/403/404 — and silently falling through would
        // hide the real error from the user.
        val log = mutableListOf<String>()
        val httpException = HttpException(
            Response.error<Any>(404, okhttp3.ResponseBody.Companion.create(null, ""))
        )
        val failingHttp = object : RadioBrowserApi {
            override suspend fun getStations(limit: Int, order: String, reverse: Boolean): List<ApiStation> =
                throw UnsupportedOperationException()
            override suspend fun searchStations(
                name: String?, country: String?, language: String?, tag: String?,
                offset: Int, limit: Int, order: String, reverse: Boolean,
            ): List<ApiStation> {
                log.add("a")
                throw httpException
            }
            override suspend fun getCountries(): List<ApiCountry> = throw UnsupportedOperationException()
            override suspend fun getLanguages(): List<ApiLanguage> = throw UnsupportedOperationException()
            override suspend fun getTags(): List<ApiTag> = throw UnsupportedOperationException()
        }
        val bApi = OrderedApi("b", stationsResult = Result.success(emptyList()), log = log)
        val store = FakeRegionStore(initialIndex = 0)

        try {
            fetchStationsPageWithFailover(listOf(failingHttp, bApi), store) { it.searchStations() }
            fail("expected HttpException")
        } catch (e: HttpException) {
            assertEquals(404, e.code())
        }
        assertEquals("HttpException must NOT trigger next-region attempt", listOf("a"), log)
    }

    @Test
    fun startIndex_picksUpPersistedCurrentIndex() = runBlocking {
        // Round-robin: persisted currentIndex=1 → first attempt is api[1].
        val log = mutableListOf<String>()
        val apis = listOf(
            OrderedApi("a", stationsResult = Result.success(emptyList()), log = log),
            OrderedApi("b", stationsResult = Result.success(emptyList()), log = log),
            OrderedApi("c", stationsResult = Result.success(emptyList()), log = log),
        )
        val store = FakeRegionStore(initialIndex = 1)

        fetchStationsPageWithFailover(apis, store) { it.searchStations() }

        assertEquals(listOf("b"), log)
        assertEquals("winner index must round-trip back into store", 1, store.currentIndex)
    }
}
