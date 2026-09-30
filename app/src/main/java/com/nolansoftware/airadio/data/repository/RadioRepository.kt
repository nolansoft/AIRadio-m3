// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.data.repository

import com.nolansoftware.airadio.data.api.RadioBrowserApi
import com.nolansoftware.airadio.data.database.dao.CountryDao
import com.nolansoftware.airadio.data.database.dao.FavoritesDao
import com.nolansoftware.airadio.data.database.dao.LanguageDao
import com.nolansoftware.airadio.data.database.dao.PagedStationCacheDao
import com.nolansoftware.airadio.data.database.dao.RecentlyPlayedDao
import com.nolansoftware.airadio.data.database.dao.StationDao
import com.nolansoftware.airadio.data.database.dao.TagDao
import com.nolansoftware.airadio.data.database.entity.FavoriteEntity
import com.nolansoftware.airadio.data.database.entity.PagedStationCacheEntity
import com.nolansoftware.airadio.data.database.entity.RecentlyPlayedEntity
import com.nolansoftware.airadio.data.repository.mapper.toCountryDomain
import com.nolansoftware.airadio.data.repository.mapper.toCountryEntities
import com.nolansoftware.airadio.data.repository.mapper.toDomain
import com.nolansoftware.airadio.data.repository.mapper.toLanguageDomain
import com.nolansoftware.airadio.data.repository.mapper.toLanguageEntities
import com.nolansoftware.airadio.data.repository.mapper.toFavoriteStationDomain
import com.nolansoftware.airadio.data.repository.mapper.toRecentStationDomain
import com.nolansoftware.airadio.data.repository.mapper.toStationDomain
import com.nolansoftware.airadio.data.repository.mapper.toDomainStations
import com.nolansoftware.airadio.data.repository.mapper.toStationEntities
import com.nolansoftware.airadio.data.repository.mapper.toTagDomain
import com.nolansoftware.airadio.data.repository.mapper.toTagEntities
import com.nolansoftware.airadio.domain.model.Country
import com.nolansoftware.airadio.domain.model.Language
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.domain.model.SyncState
import com.nolansoftware.airadio.domain.model.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RadioRepository @Inject constructor(
    private val radioBrowserApi: RadioBrowserApi,
    private val stationDao: StationDao,
    private val countryDao: CountryDao,
    private val languageDao: LanguageDao,
    private val tagDao: TagDao,
    private val favoritesDao: FavoritesDao,
    private val recentlyPlayedDao: RecentlyPlayedDao,
    private val pagedStationCacheDao: PagedStationCacheDao,
) {

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /**
     * Serializes concurrent sync runs (cold-start one-shot + daily periodic
     * + UI-driven retry). Cheap; needed because Room transactions don't
     * themselves block a second concurrent transaction that targets the
     * same table — the second one would race the API fetches and waste work.
     */
    private val syncMutex = Mutex()

    fun getPopularStations(): Flow<List<Station>> =
        stationDao.getPopularStations().map { it.toStationDomain() }

    fun getStationsByCountry(country: String): Flow<List<Station>> =
        stationDao.getStationsByCountry(country).map { it.toStationDomain() }

    fun getStationsByLanguage(language: String): Flow<List<Station>> =
        stationDao.getStationsByLanguage(language).map { it.toStationDomain() }

    fun getStationsByTag(tag: String): Flow<List<Station>> =
        stationDao.getStationsByTag(tag).map { it.toStationDomain() }

    /**
     * Returns one page of stations for the Browse > Country / Language /
     * Tag list, using [PagedStationCacheEntity] as a write-through cache.
     *
     * Cache-first: if a fresh (within [ttlMillis]) row exists at
     * (type, query, offset), returns those rows from Room directly. On a
     * miss (or expired entry) hits the network via [fetchStationsPage]
     * (which fans out into parallel chunks for limit > 1000), writes the
     * result into `paged_station_cache`, and returns the freshly-fetched
     * rows.
     *
     * What this gets us:
     * - First visit to "China": page 0 fetched from API in ~1 s, written
     *   to cache.
     * - User taps "Load next 50", "Load next 50", … — each page fetched
     *   and cached.
     * - User navigates away, comes back to China within 7 days → page 0
     *   served from cache (single Room query), no network.
     * - Pages the user has never scrolled into stay un-cached, so unused
     *   categories don't bloat the cache.
     *
     * Exactly one of [type] ∈ ["country", "language", "tag"] is interpreted;
     * passing anything else makes the network call send no filter (which
     * the API treats as top-votes) — never exercised today.
     */
    suspend fun getStationsPage(
        type: String,
        query: String,
        offset: Int,
        limit: Int,
        ttlMillis: Long = PagedStationCacheEntity.TTL_MILLIS,
    ): List<Station> = withContext(Dispatchers.IO) {
        // 1. Cache lookup — read freshness, then the page rows if fresh.
        val cachedAt = pagedStationCacheDao.newestCachedAt(type, query, offset)
        if (cachedAt != null && System.currentTimeMillis() - cachedAt < ttlMillis) {
            val cached = pagedStationCacheDao.readCachedPage(type, query, offset)
            if (cached.isNotEmpty()) {
                return@withContext cached.map { it.toStationDomain() }
            }
        }

        // 2. Network fetch (parallel-chunked for limit > CHUNK_SIZE).
        val stations = fetchStationsPage(
            country = type.takeIf { it == PagedStationCacheEntity.TYPE_COUNTRY }?.let { query },
            language = type.takeIf { it == PagedStationCacheEntity.TYPE_LANGUAGE }?.let { query },
            tag = type.takeIf { it == PagedStationCacheEntity.TYPE_TAG }?.let { query },
            offset = offset,
            limit = limit,
        )

        // 3. Write-through cache. The composite PK (queryType, queryValue,
        //    pageOffset, sortPosition) makes REPLACE idempotent on
        //    re-fetch.
        if (stations.isNotEmpty()) {
            val nowMs = System.currentTimeMillis()
            val entities = stations.mapIndexed { i, station ->
                PagedStationCacheEntity(
                    queryType = type,
                    queryValue = query,
                    pageOffset = offset,
                    sortPosition = i,
                    cachedAt = nowMs,
                    stationuuid = station.stationuuid,
                    name = station.name,
                    url = station.url,
                    url_resolved = station.urlResolved,
                    favicon = station.favicon,
                    country = station.country,
                    countrycode = station.countryCode,
                    language = station.language,
                    tags = station.tags,
                    codec = station.codec,
                    bitrate = station.bitrate,
                    votes = station.votes,
                    lastchecktime = station.lastCheckTime,
                )
            }
            pagedStationCacheDao.upsertPage(entities)
        }

        stations
    }

    /**
     * Fetch a single page of stations filtered by one of country/language/tag.
     * Used by the Paging 3 source for the StationListScreen infinite scroll.
     *
     * Exactly one of [country]/[language]/[tag] should be non-null; passing
     * multiple filters sends them all to the API which will AND them together.
     * Callers (GetStationsPagingUseCase) enforce the exactly-one invariant.
     */
    suspend fun fetchStationsPage(
        country: String? = null,
        language: String? = null,
        tag: String? = null,
        offset: Int,
        limit: Int
    ): List<Station> = withContext(Dispatchers.IO) {
        // For `limit` above the chunk threshold, fan out into parallel
        // requests of CHUNK_SIZE rows each. OkHttp uses HTTP/2 multiplexing
        // on the connection pool by default, so all chunks share one TCP
        // connection and the wall-clock cost is roughly the slowest chunk,
        // not the sum. For a 5000-row fetch from a far-away server this is
        // the difference between ~5–30s sequential and ~1–3s parallel, with
        // no change at the Paging layer.
        //
        // The Paging layer (RemoteMediator + paged_station_cache) is
        // untouched — the parallel structure is purely an internal
        // optimisation here, so the "max 100 stations" bug fixed by
        // bumping PAGE_SIZE to 5000 stays fixed: endOfPaginationReached is
        // still decided by stations.size vs the *caller's* limit, which
        // here is PAGE_SIZE = 5000, not CHUNK_SIZE.
        if (limit <= CHUNK_SIZE) {
            radioBrowserApi.searchStations(
                country = country,
                language = language,
                tag = tag,
                offset = offset,
                limit = limit,
            ).toDomainStations()
        } else {
            val numChunks = (limit + CHUNK_SIZE - 1) / CHUNK_SIZE
            coroutineScope {
                (0 until numChunks).map { chunkIdx ->
                    async {
                        val chunkOffset = offset + chunkIdx * CHUNK_SIZE
                        val chunkLimit = minOf(CHUNK_SIZE, limit - chunkIdx * CHUNK_SIZE)
                        radioBrowserApi.searchStations(
                            country = country,
                            language = language,
                            tag = tag,
                            offset = chunkOffset,
                            limit = chunkLimit,
                        ).toDomainStations()
                    }
                }.awaitAll().flatten()
            }
        }
    }

    fun getRecentlyPlayedStations(): Flow<List<Station>> =
        recentlyPlayedDao.getRecentlyPlayedEntities().map { it.toRecentStationDomain() }

    fun getFavoriteStations(): Flow<List<Station>> =
        favoritesDao.getFavoriteEntities().map { it.toFavoriteStationDomain() }

    fun getAllCountries(): Flow<List<Country>> =
        countryDao.getAllCountries().map { it.toCountryDomain() }

    fun getAllLanguages(): Flow<List<Language>> =
        languageDao.getAllLanguages().map { it.toLanguageDomain() }

    fun getPopularTags(): Flow<List<Tag>> =
        tagDao.getPopularTags().map { it.toTagDomain() }

    fun searchStations(query: String): Flow<List<Station>> =
        stationDao.searchStations(query).map { it.toStationDomain() }

    fun isFavorite(stationId: String): Flow<Boolean> =
        favoritesDao.isFavorite(stationId)

    /**
     * Look up a station by id. The `stations` table holds stations from
     * SyncWorker (top-voted worldwide) and Search results; the
     * `paged_station_cache` table holds stations the user has scrolled
     * into view on Browse > Countries / Languages / Tags. PlayerScreen
     * calls this when the user navigates from any of those entry points;
     * without the paged-cache fallback, a station browsed from a
     * paginated list would resolve to null and the Player UI would
     * blank out (audio keeps playing because the service is independent
     * of ViewModel state).
     */
    suspend fun getStationById(stationId: String): Station? =
        stationDao.getStationById(stationId)?.toDomain()
            ?: pagedStationCacheDao.findByStationId(stationId)?.toStationDomain()

    /**
     * Cold-start / banner-triggered sync. Smaller payload (~1.5 MB stations +
     * the metadata) so the user's first paint lands in a few seconds on
     * normal networks. Daily workers call [syncDailyData] instead.
     */
    suspend fun syncInitialData() = withContext(Dispatchers.IO) {
        runSync(fullSync = false)
    }

    /**
     * Daily sync. Pulls the fuller stations list (caller passes the larger
     * limit) so cached data stays complete. Identical workflow to
     * [syncInitialData] except for the station payload size.
     */
    suspend fun syncDailyData() = withContext(Dispatchers.IO) {
        runSync(fullSync = true)
    }

    /**
     * UI-driven retry entry point. Identical to [syncInitialData] today;
     * exists as a separate method so the banner doesn't tie itself to the
     * cold-start contract.
     */
    suspend fun syncNow() = syncInitialData()

    /**
     * Drive a sync run end-to-end. The four fetches are launched in
     * parallel inside [coroutineScope] so cancellation from the caller
     * (e.g. `ExistingWorkPolicy.REPLACE` cancelling the SyncWorker) tears
     * every child down. Each fetch completes its insert as soon as its
     * data arrives. Stations emit start and completion states (the UI
     * banner reads total from the start state).
     */
    private suspend fun runSync(fullSync: Boolean) {
        syncMutex.withLock {
            try {
                _syncState.value = SyncState.Syncing(SyncState.Syncing.Stage.COUNTRIES)

                coroutineScope {
                    val stationsDeferred = async {
                        val stations = if (fullSync) {
                            radioBrowserApi.getStations(limit = 1500)
                        } else {
                            radioBrowserApi.getStations() // default 300
                        }
                        val total = stations.size
                        val entities = stations.toStationEntities()
                        _syncState.value = SyncState.Syncing(
                            SyncState.Syncing.Stage.STATIONS,
                            processed = 0,
                            total = total
                        )
                        stationDao.clearAllStations()
                        stationDao.insertStations(entities)
                        _syncState.value = SyncState.Syncing(
                            SyncState.Syncing.Stage.STATIONS,
                            processed = total,
                            total = total
                        )
                    }

                    val countriesDeferred = async {
                        _syncState.value = SyncState.Syncing(SyncState.Syncing.Stage.COUNTRIES)
                        val countries = radioBrowserApi.getCountries()
                        countryDao.clearAllCountries()
                        countryDao.insertCountries(countries.toCountryEntities())
                    }

                    val languagesDeferred = async {
                        _syncState.value = SyncState.Syncing(SyncState.Syncing.Stage.LANGUAGES)
                        val languages = radioBrowserApi.getLanguages()
                        languageDao.clearAllLanguages()
                        languageDao.insertLanguages(languages.toLanguageEntities())
                    }

                    val tagsDeferred = async {
                        _syncState.value = SyncState.Syncing(SyncState.Syncing.Stage.TAGS)
                        val tags = radioBrowserApi.getTags()
                        tagDao.clearAllTags()
                        tagDao.insertTags(tags.toTagEntities())
                    }

                    awaitAll(countriesDeferred, languagesDeferred, tagsDeferred, stationsDeferred)
                }
                _syncState.value = SyncState.Success
            } catch (e: Exception) {
                _syncState.value = SyncState.Failed(
                    message = e.message ?: e.javaClass.simpleName,
                    willRetry = true
                )
                throw e
            }
        }
    }

    suspend fun addToFavorites(station: Station) {
        // Capture the full station snapshot in the favorites row. The next sync
        // may delete the matching row from `stations` (runSync clears the
        // table), but the favorites list still resolves from this snapshot —
        // see AppDatabase.MIGRATION_2_3 + FavoritesDao.getFavoriteEntities for
        // why the favorites table no longer JOINs against `stations`.
        favoritesDao.addToFavorites(
            FavoriteEntity(
                stationuuid = station.stationuuid,
                added_time = System.currentTimeMillis(),
                name = station.name,
                url = station.url,
                url_resolved = station.urlResolved,
                favicon = station.favicon,
                country = station.country,
                countrycode = station.countryCode,
                language = station.language,
                tags = station.tags,
                codec = station.codec,
                bitrate = station.bitrate,
                votes = station.votes,
                lastchecktime = station.lastCheckTime
            )
        )
    }

    suspend fun removeFromFavorites(stationId: String) {
        favoritesDao.removeFromFavorites(stationId)
    }

    suspend fun addToRecentlyPlayed(station: Station) {
        // Capture the full station snapshot in the recently_played row. The
        // next sync may delete the matching row from `stations` (runSync
        // clears the table), but the recently-played carousel still resolves
        // from this snapshot — see AppDatabase.MIGRATION_3_4 +
        // RecentlyPlayedDao.getRecentlyPlayedEntities for why the
        // `recently_played` table no longer JOINs against `stations`.
        recentlyPlayedDao.addToRecentlyPlayed(
            RecentlyPlayedEntity(
                stationuuid = station.stationuuid,
                played_time = System.currentTimeMillis(),
                name = station.name,
                url = station.url,
                url_resolved = station.urlResolved,
                favicon = station.favicon,
                country = station.country,
                countrycode = station.countryCode,
                language = station.language,
                tags = station.tags,
                codec = station.codec,
                bitrate = station.bitrate,
                votes = station.votes,
                lastchecktime = station.lastCheckTime
            )
        )

        val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        recentlyPlayedDao.cleanupOldRecentlyPlayed(cutoffTime)
    }

    companion object {
        /**
         * Parallel-fetch chunk size used by [fetchStationsPage]. Sized at 1000
         * because OkHttp's default `maxRequestsPerHost = 5` matches the
         * `PAGE_SIZE = 5000` fan-out (5 chunks × 1000 = 5000) cleanly —
         * exactly one request per HTTP/2 stream slot, no queueing.
         *
         * Each 1000-row response is roughly 1 MB, well within the 60s read
         * timeout configured in [com.nolansoftware.airadio.di.AppModule].
         */
        private const val CHUNK_SIZE = 1000
    }
}
