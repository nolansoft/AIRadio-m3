package com.nolansoftware.airadio.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nolansoftware.airadio.domain.model.Result
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.domain.usecase.GetFavoriteStationsUseCase
import com.nolansoftware.airadio.domain.usecase.GetPopularStationsUseCase
import com.nolansoftware.airadio.domain.usecase.GetRecentlyPlayedStationsUseCase
import com.nolansoftware.airadio.domain.usecase.GetStationsByCountryUseCase
import com.nolansoftware.airadio.domain.usecase.IsFavoriteUseCase
import com.nolansoftware.airadio.domain.usecase.ToggleFavoriteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val getPopularStationsUseCase: GetPopularStationsUseCase,
    private val getRecentlyPlayedStationsUseCase: GetRecentlyPlayedStationsUseCase,
    private val getStationsByCountryUseCase: GetStationsByCountryUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val isFavoriteUseCase: IsFavoriteUseCase
) : AndroidViewModel(application) {

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    val popularStations: Flow<List<Station>> = getPopularStationsUseCase()
    val recentlyPlayedStations: Flow<List<Station>> = getRecentlyPlayedStationsUseCase()

    val localStations: Flow<List<Station>> = getStationsByCountryUseCase(
        Locale.getDefault().displayCountry
    )

    init {
        // SyncWorker.schedule() is invoked from AIRadioApp.onCreate(); calling it here
        // again would be a no-op for the periodic worker (KEEP) but would replace the
        // pending one-time sync, so we skip it.
        viewModelScope.launch {
            combine(
                popularStations,
                recentlyPlayedStations,
                localStations
            ) { _, _, _ ->
                _isLoading.value = false
            }.collect {}
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