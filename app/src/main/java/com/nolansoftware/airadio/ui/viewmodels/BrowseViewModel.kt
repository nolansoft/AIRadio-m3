// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nolansoftware.airadio.domain.model.Country
import com.nolansoftware.airadio.domain.model.Language
import com.nolansoftware.airadio.domain.model.SyncState
import com.nolansoftware.airadio.domain.model.Tag
import com.nolansoftware.airadio.domain.usecase.GetAllCountriesUseCase
import com.nolansoftware.airadio.domain.usecase.GetAllLanguagesUseCase
import com.nolansoftware.airadio.domain.usecase.GetPopularTagsUseCase
import com.nolansoftware.airadio.domain.usecase.GetSyncStateUseCase
import com.nolansoftware.airadio.domain.usecase.SyncNowUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val getAllCountriesUseCase: GetAllCountriesUseCase,
    private val getAllLanguagesUseCase: GetAllLanguagesUseCase,
    private val getPopularTagsUseCase: GetPopularTagsUseCase,
    getSyncStateUseCase: GetSyncStateUseCase,
    private val syncNowUseCase: SyncNowUseCase
) : ViewModel() {

    sealed class BrowseTab {
        object Countries : BrowseTab()
        object Languages : BrowseTab()
        object Tags : BrowseTab()
    }

    private val _selectedTab = MutableStateFlow<BrowseTab>(BrowseTab.Countries)
    val selectedTab: StateFlow<BrowseTab> = _selectedTab

    // StateFlow (not cold Flow) so that when BrowseScreen recomposes after
    // returning from StationList, `collectAsState()` reads the previously
    // emitted value synchronously — no `initial = emptyList()` window during
    // which `isLoading` would resolve to true and the column would render
    // skeleton items. The skeleton column's smaller item count (10 vs the
    // 100+ real rows) causes the column's MeasurePolicy to clamp the saved
    // `firstVisibleItemIndex` and mutate the LazyListState, losing the user's
    // scroll position. See HomeViewModel for the full rationale; the same
    // pattern applies to all three lists here.
    val countries: StateFlow<List<Country>?> = getAllCountriesUseCase()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val languages: StateFlow<List<Language>?> = getAllLanguagesUseCase()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val tags: StateFlow<List<Tag>?> = getPopularTagsUseCase()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val syncState: StateFlow<SyncState> = getSyncStateUseCase()

    fun selectTab(tab: BrowseTab) {
        _selectedTab.value = tab
    }

    fun retrySync() {
        viewModelScope.launch { syncNowUseCase() }
    }
}
