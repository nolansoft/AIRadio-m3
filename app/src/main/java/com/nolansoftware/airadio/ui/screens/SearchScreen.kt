// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.nolansoftware.airadio.ads.AdMobConfig
import com.nolansoftware.airadio.ads.BannerAd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.nolansoftware.airadio.R
import com.nolansoftware.airadio.consent.ConsentManager
import com.nolansoftware.airadio.domain.model.PlayerState
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.ui.components.StationCard
import com.nolansoftware.airadio.ui.navigation.Screen
import com.nolansoftware.airadio.ui.viewmodels.PlayerViewModel
import com.nolansoftware.airadio.ui.viewmodels.SearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    searchViewModel: SearchViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel(),
    consentManager: ConsentManager
) {
    val searchQuery by searchViewModel.searchQuery.collectAsState()
    val searchResults by searchViewModel.searchResults.collectAsState(initial = emptyList())
    val playerState by playerViewModel.playerState.observeAsState(PlayerState.Idle)

    var showSettingsSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = searchViewModel::onSearchQueryChanged,
                    onSearch = {},
                    active = false,
                    onActiveChange = {},
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchViewModel.onSearchQueryChanged("") }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear_search))
                            }
                        }
                    }
                ) {}
                Spacer(Modifier.padding(start = 8.dp))
                IconButton(onClick = { showSettingsSheet = true }) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.privacy_settings_title))
                }
            }
        }
    ) { innerPadding ->
        if (showSettingsSheet) {
            val context = LocalContext.current
            val uriHandler = LocalUriHandler.current
            ModalBottomSheet(onDismissRequest = { showSettingsSheet = false }) {
                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Text(
                        text = stringResource(R.string.privacy_settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    TextButton(onClick = {
                        consentManager.showPrivacyOptions(context as android.app.Activity) {
                            showSettingsSheet = false
                        }
                    }) {
                        Text(stringResource(R.string.manage_privacy_options))
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        // BLOCKER-A per plan §4 risk #5: replaced by user before M6.
                        uriHandler.openUri("https://example.com/airadio-privacy")
                        showSettingsSheet = false
                    }) {
                        Text(stringResource(R.string.privacy_policy))
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (searchQuery.length >= 2) {
                if (searchResults.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No stations found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "Results for \"$searchQuery\"",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(
                                start = 20.dp,
                                top = 8.dp,
                                end = 20.dp,
                                bottom = 8.dp,
                            ),
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 160.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val shouldShowBanner = searchResults.size >= AdMobConfig.BANNER_SEARCH_THRESHOLD
                            val adSlotIndex = AdMobConfig.BANNER_SEARCH_POSITION
                            items(
                                count = if (shouldShowBanner) searchResults.size + 1 else searchResults.size,
                                key = { combinedIdx ->
                                    if (shouldShowBanner && combinedIdx == adSlotIndex) "ad-banner-search"
                                    else searchResults[combinedIdx - if (shouldShowBanner && combinedIdx > adSlotIndex) 1 else 0].stationuuid
                                },
                                span = { combinedIdx ->
                                    if (shouldShowBanner && combinedIdx == adSlotIndex) GridItemSpan(maxLineSpan)
                                    else GridItemSpan(1)
                                }
                            ) { combinedIdx ->
                                if (shouldShowBanner && combinedIdx == adSlotIndex) {
                                    BannerAd(modifier = Modifier.padding(vertical = 8.dp))
                                } else {
                                    val stationIdx = combinedIdx - if (shouldShowBanner && combinedIdx > adSlotIndex) 1 else 0
                                    val station = searchResults[stationIdx]
                                    StationCard(
                                        station = station,
                                        isFavorite = searchViewModel.isFavorite(station.stationuuid)
                                            .collectAsState(initial = false).value,
                                        onStationClick = {
                                            handleStationClick(
                                                station = station,
                                                navController = navController,
                                                playerViewModel = playerViewModel,
                                                playerState = playerState
                                            )
                                        },
                                        onToggleFavorite = { searchViewModel.toggleFavorite(it) }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Search for radio stations by name, country, or tag",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

private fun handleStationClick(
    station: Station,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    playerState: PlayerState
) {
    val isCurrentlyPlaying = when (playerState) {
        is PlayerState.Playing -> playerState.station.stationuuid == station.stationuuid
        is PlayerState.Paused -> playerState.station.stationuuid == station.stationuuid
        else -> false
    }

    if (!isCurrentlyPlaying) {
        playerViewModel.playStation(station)
    }
    navController.navigate(Screen.Player.createRoute(station.stationuuid))
}