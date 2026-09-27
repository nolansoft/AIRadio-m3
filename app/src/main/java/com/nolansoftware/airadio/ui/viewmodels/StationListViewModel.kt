package com.nolansoftware.airadio.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.domain.usecase.FetchStationsByCountryUseCase
import com.nolansoftware.airadio.domain.usecase.FetchStationsByLanguageUseCase
import com.nolansoftware.airadio.domain.usecase.FetchStationsByTagUseCase
import com.nolansoftware.airadio.domain.usecase.GetStationsByCountryUseCase
import com.nolansoftware.airadio.domain.usecase.GetStationsByLanguageUseCase
import com.nolansoftware.airadio.domain.usecase.GetStationsByTagUseCase
import com.nolansoftware.airadio.domain.usecase.IsFavoriteUseCase
import com.nolansoftware.airadio.domain.usecase.ToggleFavoriteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StationListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    getStationsByCountryUseCase: GetStationsByCountryUseCase,
    getStationsByLanguageUseCase: GetStationsByLanguageUseCase,
    getStationsByTagUseCase: GetStationsByTagUseCase,
    private val fetchStationsByCountryUseCase: FetchStationsByCountryUseCase,
    private val fetchStationsByLanguageUseCase: FetchStationsByLanguageUseCase,
    private val fetchStationsByTagUseCase: FetchStationsByTagUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val isFavoriteUseCase: IsFavoriteUseCase
) : ViewModel() {

    private val type: String = savedStateHandle["type"] ?: "country"
    private val query: String = savedStateHandle["query"] ?: ""

    val stations: Flow<List<Station>> = when (type) {
        "country" -> getStationsByCountryUseCase(query)
        "language" -> getStationsByLanguageUseCase(query)
        "tag" -> getStationsByTagUseCase(query)
        else -> getStationsByCountryUseCase(query)
    }

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching {
                when (type) {
                    "country" -> fetchStationsByCountryUseCase(query)
                    "language" -> fetchStationsByLanguageUseCase(query)
                    "tag" -> fetchStationsByTagUseCase(query)
                }
            }.also { _isLoading.value = false }
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
