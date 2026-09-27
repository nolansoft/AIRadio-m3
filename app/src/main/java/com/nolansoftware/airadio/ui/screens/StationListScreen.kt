package com.nolansoftware.airadio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.navigation.NavController
import com.nolansoftware.airadio.R
import com.nolansoftware.airadio.domain.model.PlayerState
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.ui.components.SkeletonStationCard
import com.nolansoftware.airadio.ui.components.StationCard
import com.nolansoftware.airadio.ui.navigation.Screen
import com.nolansoftware.airadio.ui.viewmodels.PlayerViewModel
import com.nolansoftware.airadio.ui.viewmodels.StationListViewModel

private const val SKELETON_STATION_COUNT = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationListScreen(
    navController: NavController,
    type: String,
    query: String,
    stationListViewModel: StationListViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val pagingItems = stationListViewModel.stations.collectAsLazyPagingItems()
    val playerState by playerViewModel.playerState.observeAsState(PlayerState.Idle)

    val title = when (type) {
        "country" -> "Stations in $query"
        "language" -> "Stations in $query"
        "tag" -> "Stations tagged $query"
        else -> "Stations"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val refresh = pagingItems.loadState.refresh
            val append = pagingItems.loadState.append

            when {
                // First-page load in flight and we have nothing to show yet —
                // render skeletons so the screen doesn't flash empty.
                refresh is LoadState.Loading && pagingItems.itemCount == 0 -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(SKELETON_STATION_COUNT) { SkeletonStationCard() }
                    }
                }
                // First-page load failed (and nothing was previously cached
                // in memory) — show a centered error with a Retry button.
                refresh is LoadState.Error && pagingItems.itemCount == 0 -> {
                    ErrorPanel(
                        message = refresh.error.message
                            ?: "Couldn't load stations. Check your connection.",
                        onRetry = { pagingItems.retry() }
                    )
                }
                // Either loaded successfully or the user navigated away from
                // a refresh error after items had already landed.
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            count = pagingItems.itemCount,
                            key = pagingItems.itemKey { it.stationuuid }
                        ) { index ->
                            val station = pagingItems[index] ?: return@items
                            StationCard(
                                station = station,
                                isFavorite = stationListViewModel.isFavorite(station.stationuuid)
                                    .collectAsState(initial = false).value,
                                onStationClick = {
                                    handleStationClick(
                                        station = station,
                                        navController = navController,
                                        playerViewModel = playerViewModel,
                                        playerState = playerState
                                    )
                                },
                                onToggleFavorite = { stationListViewModel.toggleFavorite(it) }
                            )
                        }

                        // Append footer: spinner while a follow-up page is
                        // in flight, or a tiny inline error message if the
                        // next page failed (Retry triggers pagingItems.retry()
                        // which re-fetches the failed page).
                        when (append) {
                            is LoadState.Loading -> item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                                }
                            }
                            is LoadState.Error -> item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Button(onClick = { pagingItems.retry() }) {
                                        Text("Retry loading more")
                                    }
                                }
                            }
                            else -> Unit
                        }

                        // Empty result after a successful load (e.g. a tag
                        // that exists in the catalogue but no station matches
                        // in /json/stations/search?tag=...).
                        if (pagingItems.itemCount == 0 &&
                            refresh is LoadState.NotLoading
                        ) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No stations found",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorPanel(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text("Retry")
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
