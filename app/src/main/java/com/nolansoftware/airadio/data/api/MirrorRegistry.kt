// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.api

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single row from `/json/servers`. The current registry schema is just
 * `{name, ip}`; we keep only the hostname ([name]) because that's what
 * Retrofit's `baseUrl` consumes — the IP isn't useful for HTTPS SNI.
 */
data class MirrorEntry(
    val name: String,
    val ip: String,
)

/**
 * Snapshot of the mirror list as last seen by the live registry, plus
 * the wall-clock time of the fetch. Returned by [MirrorCacheStore.read]
 * and consumed by [MirrorRegistry.getMirrors] for TTL-aware caching.
 */
data class MirrorCache(
    val mirrors: List<String>,
    val fetchedAt: Long,
)

/**
 * Persistence layer for [MirrorCache]. Kept as an interface so the
 * registry logic can be unit-tested with an in-memory fake.
 */
interface MirrorCacheStore {
    fun read(): MirrorCache?
    fun write(mirrors: List<String>, fetchedAt: Long)
}

/**
 * Production [MirrorCacheStore] backed by [android.content.SharedPreferences].
 * Stored under the same `radio_browser_prefs` file as [RegionStore] so
 * mirror discovery and per-launch region rotation share one disk budget.
 */
@Singleton
class SharedPrefsMirrorCacheStore @Inject constructor(
    @ApplicationContext context: Context,
) : MirrorCacheStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): MirrorCache? {
        if (!prefs.contains(KEY_MIRRORS) || !prefs.contains(KEY_FETCHED_AT)) return null
        val raw = prefs.getStringSet(KEY_MIRRORS, null) ?: return null
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        // SharedPreferences returns a SET — copy to a list so the caller can
        // safely iterate without ConcurrentModificationException when the
        // backing set is later mutated by another edit() call.
        return MirrorCache(raw.toList(), fetchedAt)
    }

    override fun write(mirrors: List<String>, fetchedAt: Long) {
        prefs.edit()
            .putStringSet(KEY_MIRRORS, mirrors.toSet())
            .putLong(KEY_FETCHED_AT, fetchedAt)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "radio_browser_prefs"
        const val KEY_MIRRORS = "mirror_list"
        const val KEY_FETCHED_AT = "mirror_list_fetched_at"
    }
}

/**
 * Discovers and caches the live Radio Browser mirror list.
 *
 * The canonical entry point is `all.api.radio-browser.info` per the
 * project's docs, but that hostname is currently broken (TLS reset) —
 * the production [MirrorRegistry] fetcher therefore targets
 * `de1.api.radio-browser.info/json/servers` directly. The registry
 * interface is parameterized over the fetcher so swapping to `all.`
 * (or any other entry point) is a one-line change.
 *
 * Strategy:
 *  1. If a cache row exists and is younger than [ttlMillis], return it
 *     without touching the network.
 *  2. Otherwise call [fetcher]. On success, deduplicate by hostname
 *     (the current registry reports IPv4 + IPv6 entries for the same
 *     host) and persist.
 *  3. On fetch failure, return the stale cache if any — a 24h-old list
 *     is still useful if it was the one that actually worked last time.
 *  4. With nothing else to go on, return [fallback].
 *
 * The empty-fetcher case (registry returns `[]`) is treated like a
 * fetch failure: better to fall back to the hardcoded list than to
 * hand `RegionFailoverSyncExecutor` an empty list and crash on its
 * `require(apis.isNotEmpty())` guard.
 */
class MirrorRegistry(
    private val fetcher: suspend () -> List<MirrorEntry>,
    private val cacheStore: MirrorCacheStore,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val fallback: List<String> = DEFAULT_FALLBACK,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun getMirrors(): List<String> {
        val now = clock()
        val cached = readCacheSafely()
        if (cached != null && now - cached.fetchedAt < ttlMillis) {
            return cached.mirrors
        }

        return try {
            val live = fetcher().map { it.name }.distinct()
            val result = if (live.isEmpty()) fallback else live
            cacheStore.write(result, now)
            result
        } catch (e: Exception) {
            // Fetch failed. Whatever was on disk before is better than the
            // hardcoded fallback if it was a working regional mirror.
            cached?.mirrors ?: fallback
        }
    }

    private fun readCacheSafely(): MirrorCache? = try {
        cacheStore.read()
    } catch (_: Exception) {
        null
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 24L * 60L * 60L * 1000L
        val DEFAULT_FALLBACK: List<String> = listOf("de1.api.radio-browser.info")
    }
}
