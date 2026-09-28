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
import com.nolansoftware.airadio.data.database.entity.RecentlyPlayedEntity
import com.nolansoftware.airadio.data.repository.mapper.toCountryDomain
import com.nolansoftware.airadio.data.repository.mapper.toCountryEntities
import com.nolansoftware.airadio.data.repository.mapper.toDomain
import com.nolansoftware.airadio.data.repository.mapper.toLanguageDomain
import com.nolansoftware.airadio.data.repository.mapper.toLanguageEntities
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
        radioBrowserApi.searchStations(
            country = country,
            language = language,
            tag = tag,
            offset = offset,
            limit = limit
        ).toDomainStations()
    }

    fun getRecentlyPlayedStations(): Flow<List<Station>> =
        stationDao.getRecentlyPlayedStations().map { it.toStationDomain() }

    fun getFavoriteStations(): Flow<List<Station>> =
        stationDao.getFavoriteStations().map { it.toStationDomain() }

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

    suspend fun addToFavorites(stationId: String) {
        favoritesDao.addToFavorites(
            FavoriteEntity(
                stationuuid = stationId,
                added_time = System.currentTimeMillis()
            )
        )
    }

    suspend fun removeFromFavorites(stationId: String) {
        favoritesDao.removeFromFavorites(stationId)
    }

    suspend fun addToRecentlyPlayed(stationId: String) {
        recentlyPlayedDao.addToRecentlyPlayed(
            RecentlyPlayedEntity(
                stationuuid = stationId,
                played_time = System.currentTimeMillis()
            )
        )

        val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        recentlyPlayedDao.cleanupOldRecentlyPlayed(cutoffTime)
    }
}
