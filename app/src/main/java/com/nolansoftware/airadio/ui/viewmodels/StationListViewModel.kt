package com.nolansoftware.airadio.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.domain.usecase.GetStationsPagingUseCase
import com.nolansoftware.airadio.domain.usecase.IsFavoriteUseCase
import com.nolansoftware.airadio.domain.usecase.ToggleFavoriteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StationListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getStationsPagingUseCase: GetStationsPagingUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val isFavoriteUseCase: IsFavoriteUseCase
) : ViewModel() {

    private val type: String = savedStateHandle["type"] ?: "country"
    private val query: String = savedStateHandle["query"] ?: ""

    /**
     * Backing flow for the screen's `collectAsLazyPagingItems()`. The
     * use case wires `cachedIn(viewModelScope)` so configuration
     * changes don't restart the page stream from offset 0.
     */
    val stations: Flow<PagingData<Station>> =
        getStationsPagingUseCase(type, query, viewModelScope)

    fun toggleFavorite(station: Station) {
        viewModelScope.launch {
            toggleFavoriteUseCase(station.stationuuid)
        }
    }

    fun isFavorite(stationId: String): Flow<Boolean> {
        return isFavoriteUseCase(stationId)
    }
}
