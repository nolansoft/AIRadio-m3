package com.nolansoftware.airadio.domain.usecase

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.nolansoftware.airadio.data.repository.RadioRepository
import com.nolansoftware.airadio.data.repository.paging.StationPagingSource
import com.nolansoftware.airadio.domain.model.Country
import com.nolansoftware.airadio.domain.model.Language
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.domain.model.SyncState
import com.nolansoftware.airadio.domain.model.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    suspend operator fun invoke(stationId: String) {
        val isFavorite = repository.isFavorite(stationId).first()
        if (isFavorite) {
            repository.removeFromFavorites(stationId)
        } else {
            repository.addToFavorites(stationId)
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
 * The flow is cached in [scope] (typically the screen's `viewModelScope`)
 * so configuration changes don't re-fetch from offset 0.
 *
 * [type] is one of "country" / "language" / "tag"; unknown values fall
 * back to country for forward-compatibility with new browse dimensions.
 */
class GetStationsPagingUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    operator fun invoke(type: String, query: String, scope: CoroutineScope): Flow<PagingData<Station>> {
        val fetch: suspend (offset: Int, limit: Int) -> List<Station> = { offset, limit ->
            when (type) {
                "country" -> repository.fetchStationsPage(country = query, offset = offset, limit = limit)
                "language" -> repository.fetchStationsPage(language = query, offset = offset, limit = limit)
                "tag" -> repository.fetchStationsPage(tag = query, offset = offset, limit = limit)
                else -> repository.fetchStationsPage(country = query, offset = offset, limit = limit)
            }
        }
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = PAGE_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = { StationPagingSource(fetch) }
        ).flow.cachedIn(scope)
    }

    companion object {
        // Matches Home's DAO LIMIT (Daos.kt:18). The API caps a single
        // request at this size; the Pager asks for it on every append.
        const val PAGE_SIZE = 100
    }
}