package com.nolansoftware.airadio.domain.usecase

import com.nolansoftware.airadio.data.repository.RadioRepository
import com.nolansoftware.airadio.domain.model.Country
import com.nolansoftware.airadio.domain.model.Language
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.domain.model.SyncState
import com.nolansoftware.airadio.domain.model.Tag
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

class FetchStationsByCountryUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    suspend operator fun invoke(country: String) {
        repository.fetchStationsByCountry(country)
    }
}

class FetchStationsByLanguageUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    suspend operator fun invoke(language: String) {
        repository.fetchStationsByLanguage(language)
    }
}

class FetchStationsByTagUseCase @Inject constructor(
    private val repository: RadioRepository
) {
    suspend operator fun invoke(tag: String) {
        repository.fetchStationsByTag(tag)
    }
}