// SPDX-License-Identifier: Apache-2.0

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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
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

    // StateFlow (not cold Flow) so that when HomeScreen is re-composed after
    // returning from Player, `collectAsState()` reads the previously-emitted
    // value synchronously — there is no "initialValue = null" window during
    // which the grid would have to fall back to skeleton items. With a Flow,
    // the producer restarts with null on every composition recreation, and the
    // grid's MeasurePolicy clamps the saved `firstVisibleItemIndex` against
    // the smaller skeleton item count, mutating the state and losing the
    // user's scroll position. StateFlow's retained value keeps the grid's
    // item count stable across the composition recreation, so the saved index
    // anchors correctly against the same real items that were there before
    // navigation.
    //
    // SharingStarted.Eagerly is fine here: HomeViewModel is scoped to the
    // HomeScreen NavBackStackEntry, so the upstream Flow is only collected
    // while the Home tab is in the back stack. Room's invalidation tracker
    // handles query freshness — eager collection just keeps the cache warm.
    val popularStations: StateFlow<List<Station>?> = getPopularStationsUseCase()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val recentlyPlayedStations: StateFlow<List<Station>?> = getRecentlyPlayedStationsUseCase()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val localStations: StateFlow<List<Station>?> = getStationsByCountryUseCase(
        Locale.getDefault().displayCountry
    ).stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
            toggleFavoriteUseCase(station)
        }
    }

    fun isFavorite(stationId: String): Flow<Boolean> {
        return isFavoriteUseCase(stationId)
    }
}
