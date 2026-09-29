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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
     *
     * [retryAttempt] is the 1-based index of an in-flight auto-retry (0
     * means "not retrying"); the UI shows "Retrying (1/3)…" etc. while
     * non-zero. Resets to 0 on success or when the user gives up.
     */
    data class UiState(
        val stations: List<Station> = emptyList(),
        val isLoadingFirstPage: Boolean = false,
        val isLoadingMore: Boolean = false,
        val endOfReached: Boolean = false,
        val error: String? = null,
        val retryAttempt: Int = 0,
        val maxRetries: Int = MAX_AUTO_RETRIES,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Tracked so a manual Retry tap can cancel any in-flight retry loop
     * before starting a fresh one — keeps the count and the visible
     * "Retrying (N/M)…" state coherent.
     */
    private var loadingJob: Job? = null

    init {
        loadNextPage()
    }

    /**
     * Loads the next [GetStationsByFilterUseCase.PAGE_SIZE] stations and
     * appends them to [UiState.stations]. Idempotent: a second call while
     * a load is in flight is dropped, so the UI can call this from both
     * `init` and the button without race conditions.
     *
     * On a network failure the call is auto-retried up to [MAX_AUTO_RETRIES]
     * times with exponential backoff ([RETRY_DELAY_MS] × 2ⁿ⁻¹, i.e. 2s,
     * 4s, 8s). Each retry surfaces its 1-based attempt number via
     * [UiState.retryAttempt] so the UI can show "Retrying 2/3…". After
     * the final retry fails the catch path sets [UiState.error] and the
     * user can tap Retry manually (which resets the counter).
     *
     * Cache-first happens inside the use case (Room `paged_station_cache`,
     * 7-day TTL) — by the time we return here the data is ready to render.
     */
    fun loadNextPage() {
        val current = _state.value
        if (current.isLoadingFirstPage || current.isLoadingMore || current.endOfReached) return

        // Cancel any previous in-flight load. The idempotency guard above
        // catches the common case (UI tap while isLoadingFirstPage), but
        // a manual Retry after an error arrives with isLoadingFirstPage =
        // false, so the guard wouldn't fire and we'd otherwise run two
        // retry loops in parallel.
        loadingJob?.cancel()

        val isFirst = current.stations.isEmpty()
        _state.update {
            it.copy(
                isLoadingFirstPage = isFirst,
                isLoadingMore = !isFirst,
                error = null,
                retryAttempt = 0,
            )
        }

        loadingJob = viewModelScope.launch {
            var attempt = 0
            while (true) {
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
                            retryAttempt = 0,
                        )
                    }
                    return@launch
                } catch (e: Exception) {
                    attempt++
                    if (attempt > MAX_AUTO_RETRIES) {
                        _state.update {
                            it.copy(
                                isLoadingFirstPage = false,
                                isLoadingMore = false,
                                error = e.message
                                    ?: "Couldn't load stations. Tap Retry to try again.",
                                retryAttempt = 0,
                            )
                        }
                        return@launch
                    }
                    // Surface attempt number so the UI shows "Retrying N/M…".
                    _state.update { it.copy(retryAttempt = attempt) }
                    // Exponential backoff: 2s, 4s, 8s.
                    delay(RETRY_DELAY_MS shl (attempt - 1))
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

    companion object {
        /**
         * Max auto-retry attempts on a network failure before surfacing
         * the error to the user. With exponential backoff (2s, 4s, 8s) the
         * worst-case wait before the user sees an error is ~14s + the
         * first call's full timeout. Manual Retry after that resets the
         * counter.
         */
        private const val MAX_AUTO_RETRIES = 3

        /**
         * Base delay before the first retry; doubled for each subsequent
         * attempt (2 s, 4 s, 8 s).
         */
        private const val RETRY_DELAY_MS = 2000L
    }
}