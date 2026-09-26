package com.nolansoftware.airadio.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.domain.usecase.GetFavoriteStationsUseCase
import com.nolansoftware.airadio.domain.usecase.GetStationsByCountryUseCase
import com.nolansoftware.airadio.domain.usecase.GetStationsByLanguageUseCase
import com.nolansoftware.airadio.domain.usecase.GetStationsByTagUseCase
import com.nolansoftware.airadio.domain.usecase.IsFavoriteUseCase
import com.nolansoftware.airadio.domain.usecase.ToggleFavoriteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StationListViewModel @Inject constructor(
    private val getStationsByCountryUseCase: GetStationsByCountryUseCase,
    private val getStationsByLanguageUseCase: GetStationsByLanguageUseCase,
    private val getStationsByTagUseCase: GetStationsByTagUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val isFavoriteUseCase: IsFavoriteUseCase
) : ViewModel() {

    fun getStations(type: String, query: String): Flow<List<Station>> {
        return when (type) {
            "country" -> getStationsByCountryUseCase(query)
            "language" -> getStationsByLanguageUseCase(query)
            "tag" -> getStationsByTagUseCase(query)
            else -> getStationsByCountryUseCase(query)
        }
    }

    fun toggleFavorite(station: Station) {
        viewModelScope.launch {
            toggleFavoriteUseCase(station.stationuuid)
        }
    }

    fun isFavorite(stationId: String): Flow<Boolean> {
        return isFavoriteUseCase(stationId)
    }
}