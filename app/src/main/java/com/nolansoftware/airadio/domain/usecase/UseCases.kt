// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.domain.usecase

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.nolansoftware.airadio.data.database.dao.PagedStationCacheDao
import com.nolansoftware.airadio.data.repository.RadioRepository
import com.nolansoftware.airadio.data.repository.mapper.toStationDomain
import com.nolansoftware.airadio.data.repository.paging.StationRemoteMediator
import com.nolansoftware.airadio.domain.model.Country
import com.nolansoftware.airadio.domain.model.Language
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.domain.model.SyncState
import com.nolansoftware.airadio.domain.model.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class GetPopularStationsUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    operator fun invoke(): Flow<List<Station>> = repository.getPopularStations()
}

class GetRecentlyPlayedStationsUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    operator fun invoke(): Flow<List<Station>> = repository.getRecentlyPlayedStations()
}

class GetStationsByCountryUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    operator fun invoke(country: String): Flow<List<Station>> =
        repository.getStationsByCountry(country)
}

class GetAllCountriesUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    operator fun invoke(): Flow<List<Country>> = repository.getAllCountries()
}

class GetAllLanguagesUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    operator fun invoke(): Flow<List<Language>> = repository.getAllLanguages()
}

class GetPopularTagsUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    operator fun invoke(): Flow<List<Tag>> = repository.getPopularTags()
}

class GetStationsByLanguageUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    operator fun invoke(language: String): Flow<List<Station>> =
        repository.getStationsByLanguage(language)
}

class GetStationsByTagUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    operator fun invoke(tag: String): Flow<List<Station>> =
        repository.getStationsByTag(tag)
}

class SearchStationsUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    operator fun invoke(query: String): Flow<List<Station>> =
        repository.searchStations(query)
}

class GetFavoriteStationsUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    operator fun invoke(): Flow<List<Station>> = repository.getFavoriteStations()
}

class IsFavoriteUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    operator fun invoke(stationId: String): Flow<Boolean> =
        repository.isFavorite(stationId)
}

class ToggleFavoriteUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    /**
     * Takes the full [Station] (not just the stationuuid) because adding to
     * favorites now persists a snapshot of the station's display fields — see
     * [RadioRepository.addToFavorites]. The snapshot is what makes favorites
     * survive the daily `StationDao.clearAllStations()` cycle; without it, a
     * favorited station that drops out of the popular top-N becomes invisible
     * in FavoritesScreen even though `isFavorite(stationId)` is still true.
     */
    suspend operator fun invoke(station: Station) {
        val isFavorite = repository.isFavorite(station.stationuuid).first()
        if (isFavorite) {
            repository.removeFromFavorites(station.stationuuid)
        } else {
            repository.addToFavorites(station)
        }
    }
}

class AddToRecentlyPlayedUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    suspend operator fun invoke(stationId: String) {
        repository.addToRecentlyPlayed(stationId)
    }
}

class SyncDataUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    suspend operator fun invoke() {
        repository.syncInitialData()
    }
}

class GetSyncStateUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    operator fun invoke(): StateFlow<SyncState> = repository.syncState
}

class SyncNowUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    suspend operator fun invoke() {
        repository.syncNow()
    }
}

class GetStationByIdUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    suspend operator fun invoke(stationId: String): Station? =
        repository.getStationById(stationId)
}

/**
 * Returns a cold [Flow] of [PagingData] for the country/language/tag list.
 *
 * The Pager is backed by [PagedStationCacheDao.pagingSource] (Room
 * PagingSource) for cached rows plus [StationRemoteMediator] for the
 * network fill, TTL guard, and APPEND behavior. The flow is cached in
 * [scope] (typically the screen's `viewModelScope`) so configuration
 * changes don't re-fetch from offset 0.
 *
 * [type] is one of "country" / "language" / "tag". The only callers in
 * this codebase ([BrowseScreen]) pass exactly these three values; an
 * unknown type causes [StationRemoteMediator] to send a request with
 * all three filters null (which the API treats as top-votes), but that
 * branch is not exercised today.
 */
class GetStationsPagingUseCase @Inject constructor(
    private val repository: RadioRepository,
    private val dao: PagedStationCacheDao,
) {
    @OptIn(androidx.paging.ExperimentalPagingApi::class)
    operator fun invoke(type: String, query: String, scope: CoroutineScope): Flow<PagingData<Station>> {
        val pager = Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = PAGE_SIZE,
                enablePlaceholders = false,
            ),
            remoteMediator = StationRemoteMediator(
                type = type,
                query = query,
                repository = repository,
                dao = dao,
            ),
            pagingSourceFactory = { dao.pagingSource(type, query) },
        )
        return pager.flow
            .map { pagingData -> pagingData.map { it.toStationDomain() } }
            .cachedIn(scope)
    }

    companion object {
        // Matches the API's per-request page size; the API caps a single
        // request at this many rows.
        const val PAGE_SIZE = 100

        // 7 days. A page older than this on REFRESH is silently refetched
        // when the network is available.
        const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}