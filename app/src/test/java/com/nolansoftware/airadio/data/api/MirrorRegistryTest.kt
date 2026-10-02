// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * Live mirror-list discovery for the Radio Browser API.
 *
 * Per the project docs the canonical entry point is
 * `all.api.radio-browser.info`, which is currently broken (TLS reset).
 * The de1 mirror exposes the same `/json/servers` endpoint, so the
 * production fetcher targets de1 directly and the registry transparently
 * swaps to a different entry point if/when `all.` comes back.
 *
 * `MirrorRegistry` is the place where that fetching, deduplication,
 * TTL caching, and hardcoded fallback all live — none of that logic
 * touches `RegionFailoverSyncExecutor` or `RadioRepository`, so the
 * two pieces can be tested in isolation.
 */
class MirrorRegistryTest {

    /** In-memory cache store — records the last write for assertions. */
    private class FakeStore(initial: MirrorCache? = null) : MirrorCacheStore {
        var lastWritten: Pair<List<String>, Long>? = null
            private set
        private var current: MirrorCache? = initial
        override fun read(): MirrorCache? = current
        override fun write(mirrors: List<String>, fetchedAt: Long) {
            current = MirrorCache(mirrors, fetchedAt)
            lastWritten = mirrors to fetchedAt
        }
    }

    @Test
    fun getMirrors_returnsLiveList_whenFetcherSucceeds_andPersistsToStore() = runBlocking {
        val store = FakeStore()
        val registry = MirrorRegistry(
            fetcher = { listOf(MirrorEntry("de1.api.radio-browser.info", "91.98.4.78")) },
            cacheStore = store,
            clock = { 1_000L },
        )

        val mirrors = registry.getMirrors()

        assertEquals(listOf("de1.api.radio-browser.info"), mirrors)
        assertEquals(listOf("de1.api.radio-browser.info") to 1_000L, store.lastWritten)
    }

    @Test
    fun getMirrors_dedupesDuplicateHostnames_fromRegistry() = runBlocking {
        // de1 currently reports two entries (IPv4 + IPv6) with the same
        // hostname — a naive map would hand back a duplicate. The registry
        // must collapse by name so we don't try the same host twice.
        val store = FakeStore()
        val registry = MirrorRegistry(
            fetcher = {
                listOf(
                    MirrorEntry("de1.api.radio-browser.info", "91.98.4.78"),
                    MirrorEntry("de1.api.radio-browser.info", "2a01:4f8:1c1d:699::1"),
                )
            },
            cacheStore = store,
            clock = { 1L },
        )

        val mirrors = registry.getMirrors()

        assertEquals(listOf("de1.api.radio-browser.info"), mirrors)
    }

    @Test
    fun getMirrors_servesFromCache_whenCacheIsFresh_andDoesNotCallFetcher() = runBlocking {
        val store = FakeStore(
            initial = MirrorCache(
                mirrors = listOf("cached.api.radio-browser.info"),
                fetchedAt = 1_000L,
            ),
        )
        var fetcherCalls = 0
        val registry = MirrorRegistry(
            fetcher = {
                fetcherCalls++
                listOf(MirrorEntry("live.api.radio-browser.info", "10.0.0.1"))
            },
            cacheStore = store,
            ttlMillis = 60_000L,
            clock = { 1_500L }, // 500ms later — well within TTL
        )

        val mirrors = registry.getMirrors()

        assertEquals(listOf("cached.api.radio-browser.info"), mirrors)
        assertEquals("fetcher must not be called while cache is fresh", 0, fetcherCalls)
    }

    @Test
    fun getMirrors_refreshesCache_whenCacheIsStale() = runBlocking {
        val store = FakeStore(
            initial = MirrorCache(
                mirrors = listOf("stale.api.radio-browser.info"),
                fetchedAt = 1_000L,
            ),
        )
        val registry = MirrorRegistry(
            fetcher = { listOf(MirrorEntry("fresh.api.radio-browser.info", "10.0.0.1")) },
            cacheStore = store,
            ttlMillis = 60_000L,
            clock = { 1_000L + 120_000L }, // 2 minutes later — past TTL
        )

        val mirrors = registry.getMirrors()

        assertEquals(listOf("fresh.api.radio-browser.info"), mirrors)
    }

    @Test
    fun getMirrors_fallsBackToStaleCache_whenFetcherThrows_andCacheExists() = runBlocking {
        // The user is on a CN ISP, the live fetch just failed, but yesterday
        // they successfully synced and we cached a working mirror. Serve that
        // stale list rather than nothing — a 24h-stale list is still better
        // than the hardcoded fallback if a regional mirror was working.
        val store = FakeStore(
            initial = MirrorCache(
                mirrors = listOf("old-but-works.api.radio-browser.info"),
                fetchedAt = 1_000L,
            ),
        )
        val registry = MirrorRegistry(
            fetcher = { throw IOException("network down") },
            cacheStore = store,
            ttlMillis = 60_000L,
            clock = { 1_000L + 120_000L }, // past TTL, so it WILL try to fetch
        )

        val mirrors = registry.getMirrors()

        assertEquals(listOf("old-but-works.api.radio-browser.info"), mirrors)
    }

    @Test
    fun getMirrors_fallsBackToHardcodedList_whenFetcherThrows_andCacheIsEmpty() = runBlocking {
        val store = FakeStore() // no cached entry
        val hardcoded = listOf("de1.api.radio-browser.info")
        val registry = MirrorRegistry(
            fetcher = { throw IOException("network down") },
            cacheStore = store,
            fallback = hardcoded,
            clock = { 1L },
        )

        val mirrors = registry.getMirrors()

        assertEquals(hardcoded, mirrors)
    }

    @Test
    fun getMirrors_returnsEmptyFallback_whenBothFetcherAndCacheFail_andFallbackIsEmpty() = runBlocking {
        val store = FakeStore()
        val registry = MirrorRegistry(
            fetcher = { throw IOException("network down") },
            cacheStore = store,
            fallback = emptyList(),
            clock = { 1L },
        )

        val mirrors = registry.getMirrors()

        assertEquals(emptyList<String>(), mirrors)
    }

    @Test
    fun getMirrors_returnsFallback_whenFetcherSucceedsWithEmptyList_andCacheIsEmpty() = runBlocking {
        // Defensive: if the registry returns an empty array (no mirrors
        // currently registered), don't hand back an empty list — that would
        // make `RegionFailoverSyncExecutor.execute` throw at the `apis.isNotEmpty()`
        // guard. Use the hardcoded fallback instead.
        val store = FakeStore()
        val hardcoded = listOf("de1.api.radio-browser.info")
        val registry = MirrorRegistry(
            fetcher = { emptyList() },
            cacheStore = store,
            fallback = hardcoded,
            clock = { 1L },
        )

        val mirrors = registry.getMirrors()

        assertEquals(hardcoded, mirrors)
    }

    @Test
    fun getMirrors_fallsBackToEmpty_whenFetcherThrows_andCacheAlsoThrows() = runBlocking {
        val store = object : MirrorCacheStore {
            override fun read(): MirrorCache? = throw IOException("disk corrupt")
            override fun write(mirrors: List<String>, fetchedAt: Long) = Unit
        }
        val hardcoded = listOf("de1.api.radio-browser.info")
        val registry = MirrorRegistry(
            fetcher = { throw IOException("network down") },
            cacheStore = store,
            fallback = hardcoded,
            clock = { 1L },
        )

        val mirrors = registry.getMirrors()

        assertEquals(hardcoded, mirrors)
    }

    @Test
    fun getMirrors_doesNotPersist_whenFetcherThrows() = runBlocking {
        val store = FakeStore()
        val registry = MirrorRegistry(
            fetcher = { throw IOException("transient") },
            cacheStore = store,
            fallback = listOf("de1.api.radio-browser.info"),
            clock = { 1L },
        )

        registry.getMirrors()

        assertEquals(null, store.lastWritten)
    }
}
