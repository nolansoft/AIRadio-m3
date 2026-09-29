// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.domain.usecase.GetStationsByFilterUseCase
import com.nolansoftware.airadio.domain.usecase.IsFavoriteUseCase
import com.nolansoftware.airadio.domain.usecase.ToggleFavoriteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StationListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getStationsByFilterUseCase: GetStationsByFilterUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val isFavoriteUseCase: IsFavoriteUseCase
) : ViewModel() {

    private val type: String = savedStateHandle["type"] ?: "country"
    private val query: String = savedStateHandle["query"] ?: ""

    /**
     * Screen state. The list grows by appending pages on demand via
     * [loadNextPage]. This replaces the previous Paging-3 / RemoteMediator
     * pipeline so we can show a small first batch quickly and let the user
     * drive further pages explicitly through a "Load next" button.
     */
    data class UiState(
        val stations: List<Station> = emptyList(),
        val isLoadingFirstPage: Boolean = false,
        val isLoadingMore: Boolean = false,
        val endOfReached: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        loadNextPage()
    }

    /**
     * Loads the next [GetStationsByFilterUseCase.PAGE_SIZE] stations and
     * appends them to [UiState.stations]. Idempotent: a second call while
     * a load is in flight is dropped, so the UI can call this from both
     * `init` and the button without race conditions.
     *
     * Cache-first happens inside the use case (Room `paged_station_cache`,
     * 7-day TTL) — by the time we return here the data is ready to render.
     */
    fun loadNextPage() {
        val current = _state.value
        if (current.isLoadingFirstPage || current.isLoadingMore || current.endOfReached) return

        val isFirst = current.stations.isEmpty()
        _state.update {
            it.copy(
                isLoadingFirstPage = isFirst,
                isLoadingMore = !isFirst,
                error = null,
            )
        }

        viewModelScope.launch {
            try {
                val offset = _state.value.stations.size
                val page = getStationsByFilterUseCase(
                    type = type,
                    query = query,
                    offset = offset,
                    limit = GetStationsByFilterUseCase.PAGE_SIZE,
                )
                _state.update {
                    it.copy(
                        stations = it.stations + page,
                        isLoadingFirstPage = false,
                        isLoadingMore = false,
                        // API returns fewer rows than asked → no more pages.
                        endOfReached = page.size < GetStationsByFilterUseCase.PAGE_SIZE,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoadingFirstPage = false,
                        isLoadingMore = false,
                        error = e.message ?: "Couldn't load more stations",
                    )
                }
            }
        }
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