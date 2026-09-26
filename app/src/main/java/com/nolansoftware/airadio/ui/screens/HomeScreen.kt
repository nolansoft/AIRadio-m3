package com.nolansoftware.airadio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.nolansoftware.airadio.R
import com.nolansoftware.airadio.domain.model.PlayerState
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.ui.components.StationCard
import com.nolansoftware.airadio.ui.navigation.Screen
import com.nolansoftware.airadio.ui.viewmodels.HomeViewModel
import com.nolansoftware.airadio.ui.viewmodels.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    homeViewModel: HomeViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val isLoading by homeViewModel.isLoading.collectAsState()
    val popularStations by homeViewModel.popularStations.collectAsState(initial = emptyList())
    val recentlyPlayed by homeViewModel.recentlyPlayedStations.collectAsState(initial = emptyList())
    val localStations by homeViewModel.localStations.collectAsState(initial = emptyList())
    val playerState by playerViewModel.playerState.observeAsState(PlayerState.Idle)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) }
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.loading),
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (recentlyPlayed.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.recently_played),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    item {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            content = {
                                items(recentlyPlayed) { station ->
                                    StationItem(
                                        station = station,
                                        navController = navController,
                                        homeViewModel = homeViewModel,
                                        playerViewModel = playerViewModel,
                                        playerState = playerState
                                    )
                                }
                            }
                        )
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.popular_stations),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                items(popularStations) { station ->
                    StationCard(
                        station = station,
                        isFavorite = homeViewModel.isFavorite(station.stationuuid)
                            .collectAsState(initial = false).value,
                        onStationClick = {
                            handleStationClick(
                                station = station,
                                navController = navController,
                                playerViewModel = playerViewModel,
                                playerState = playerState
                            )
                        },
                        onToggleFavorite = { homeViewModel.toggleFavorite(it) }
                    )
                }

                if (localStations.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.local_stations),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(16.dp)
                        )
                    }

                    items(localStations.take(10)) { station ->
                        StationCard(
                            station = station,
                            isFavorite = homeViewModel.isFavorite(station.stationuuid)
                                .collectAsState(initial = false).value,
                            onStationClick = {
                                handleStationClick(
                                    station = station,
                                    navController = navController,
                                    playerViewModel = playerViewModel,
                                    playerState = playerState
                                )
                            },
                            onToggleFavorite = { homeViewModel.toggleFavorite(it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LazyItemScope.StationItem(
    station: Station,
    navController: NavController,
    homeViewModel: HomeViewModel,
    playerViewModel: PlayerViewModel,
    playerState: PlayerState,
    modifier: Modifier = Modifier
) {
    StationCard(
        station = station,
        isFavorite = homeViewModel.isFavorite(station.stationuuid)
            .collectAsState(initial = false).value,
        onStationClick = {
            handleStationClick(
                station = station,
                navController = navController,
                playerViewModel = playerViewModel,
                playerState = playerState
            )
        },
        onToggleFavorite = { homeViewModel.toggleFavorite(it) },
        modifier = modifier.fillParentMaxWidth(0.85f)
    )
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