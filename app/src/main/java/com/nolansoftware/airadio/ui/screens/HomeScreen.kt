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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.nolansoftware.airadio.R
import com.nolansoftware.airadio.domain.model.PlayerState
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.ui.components.SkeletonStationCard
import com.nolansoftware.airadio.ui.components.StationCard
import com.nolansoftware.airadio.ui.components.SyncStatusBanner
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
    // The three Room-backed lists are read with `produceState(initialValue = null)`
    // rather than `collectAsState(initial = emptyList())`. With the latter,
    // HomeScreen recomposing after returning from the Player screen would start
    // for one frame with each list as `emptyList()` — and the data LazyColumn's
    // `rememberLazyListState()` restored from `rememberSaveable` would be
    // silently clamped to index 0 by the column's MeasurePolicy (no items yet
    // => nothing to anchor the saved index). By the time the Room Flow re-emits
    // the cached data, the LazyListState's firstVisibleItemIndex is already 0,
    // and the saved scroll position is lost.
    //
    // `null` here is "Flow hasn't emitted yet"; an empty list is "Flow emitted
    // and there's no data". The data LazyColumn is only composed when all three
    // are non-null, so the saved LazyListState is never asked to anchor against
    // an empty list.
    val popularStations by produceState<List<Station>?>(initialValue = null) {
        homeViewModel.popularStations.collect { value = it }
    }
    val recentlyPlayed by produceState<List<Station>?>(initialValue = null) {
        homeViewModel.recentlyPlayedStations.collect { value = it }
    }
    val localStations by produceState<List<Station>?>(initialValue = null) {
        homeViewModel.localStations.collect { value = it }
    }
    val playerState by playerViewModel.playerState.observeAsState(PlayerState.Idle)
    val syncState by homeViewModel.syncState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            SyncStatusBanner(
                syncState = syncState,
                onRetry = { homeViewModel.retrySync() }
            )

            // popularStations is the gating signal: the outer LazyColumn only
            // composes once it has emitted at least once. This keeps
            // rememberLazyListState() from being asked to anchor against an
            // empty list, which is what was clobbering the saved scroll
            // position when the user returned from the Player screen.
            val popularStationsList = popularStations
            if (popularStationsList == null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(SKELETON_COUNT) { SkeletonStationCard() }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (!recentlyPlayed.isNullOrEmpty()) {
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
                                    items(
                                        items = recentlyPlayed.orEmpty(),
                                        // Stable key on stationuuid. Without this the
                                        // LazyColumn tracks items by index; a re-emission
                                        // of recentlyPlayed (e.g. after addToRecentlyPlayed
                                        // fires from PlayerViewModel) shifts every later
                                        // section down by one and the saved first-visible
                                        // index now points to a different station — Compose
                                        // reconciles by jumping back to the top.
                                        key = { it.stationuuid }
                                    ) { station ->
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

                    items(
                        items = popularStationsList,
                        // See the recentlyPlayed key comment above. popularStations
                        // is the section the user is most likely to scroll deep
                        // into; losing the anchor on return is what made this bug
                        // visible.
                        key = { it.stationuuid }
                    ) { station ->
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

                    if (!localStations.isNullOrEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.local_stations),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(16.dp)
                            )
                        }

                        items(
                            items = localStations.orEmpty().take(10),
                            key = { it.stationuuid }
                        ) { station ->
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
}

private const val SKELETON_COUNT = 8

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
