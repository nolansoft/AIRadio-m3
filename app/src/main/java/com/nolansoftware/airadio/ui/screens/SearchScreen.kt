package com.nolansoftware.airadio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.nolansoftware.airadio.R
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
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val searchQuery by searchViewModel.searchQuery.collectAsState()
    val searchResults by searchViewModel.searchResults.collectAsState(initial = emptyList())
    val playerState by playerViewModel.playerState.observeAsState(PlayerState.Idle)

    Scaffold(
        topBar = {
            SearchBar(
                query = searchQuery,
                onQueryChange = searchViewModel::onSearchQueryChanged,
                onSearch = {},
                active = false,
                onActiveChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchViewModel.onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                }
            ) {}
        }
    ) { innerPadding ->
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = searchResults,
                            key = { it.stationuuid }
                        ) { station ->
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