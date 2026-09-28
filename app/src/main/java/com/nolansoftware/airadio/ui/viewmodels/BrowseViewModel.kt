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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    val countries: Flow<List<Country>> = getAllCountriesUseCase()
    val languages: Flow<List<Language>> = getAllLanguagesUseCase()
    val tags: Flow<List<Tag>> = getPopularTagsUseCase()

    val syncState: StateFlow<SyncState> = getSyncStateUseCase()

    fun selectTab(tab: BrowseTab) {
        _selectedTab.value = tab
    }

    fun retrySync() {
        viewModelScope.launch { syncNowUseCase() }
    }
}
