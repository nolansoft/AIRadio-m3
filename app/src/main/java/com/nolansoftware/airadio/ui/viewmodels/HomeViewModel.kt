package com.nolansoftware.airadio.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.domain.model.SyncState
import com.nolansoftware.airadio.domain.usecase.GetFavoriteStationsUseCase
import com.nolansoftware.airadio.domain.usecase.GetPopularStationsUseCase
import com.nolansoftware.airadio.domain.usecase.GetRecentlyPlayedStationsUseCase
import com.nolansoftware.airadio.domain.usecase.GetStationsByCountryUseCase
import com.nolansoftware.airadio.domain.usecase.GetSyncStateUseCase
import com.nolansoftware.airadio.domain.usecase.IsFavoriteUseCase
import com.nolansoftware.airadio.domain.usecase.SyncNowUseCase
import com.nolansoftware.airadio.domain.usecase.ToggleFavoriteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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
    private val isFavoriteUseCase: IsFavoriteUseCase,
    getSyncStateUseCase: GetSyncStateUseCase,
    private val syncNowUseCase: SyncNowUseCase
) : AndroidViewModel(application) {

    val popularStations: Flow<List<Station>> = getPopularStationsUseCase()
    val recentlyPlayedStations: Flow<List<Station>> = getRecentlyPlayedStationsUseCase()

    val localStations: Flow<List<Station>> = getStationsByCountryUseCase(
        Locale.getDefault().displayCountry
    )

    /**
     * Public seam for the banner retry CTA. Idempotent if a sync is already
     * running — the repository's Mutex serializes the calls.
     */
    val syncState: StateFlow<SyncState> = getSyncStateUseCase()

    fun retrySync() {
        viewModelScope.launch { syncNowUseCase() }
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
