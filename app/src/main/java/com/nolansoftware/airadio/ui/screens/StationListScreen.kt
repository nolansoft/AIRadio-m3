// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.nolansoftware.airadio.R
import com.nolansoftware.airadio.ads.AdMobConfig
import com.nolansoftware.airadio.ads.BannerAd
import com.nolansoftware.airadio.ads.bannerAdItem
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
    val state by stationListViewModel.state.collectAsState()
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
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // First-page load in flight and we have nothing to show yet —
            // render skeletons so the screen doesn't flash empty, plus
            // an explicit progress indicator + status text so the user
            // sees feedback while waiting (and "Retrying N/M…" during
            // auto-retries — see StationListViewModel.loadNextPage).
            if (state.isLoadingFirstPage && state.stations.isEmpty()) {
                items(count = SKELETON_STATION_COUNT) { SkeletonStationCard() }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            text = when {
                                state.retryAttempt > 0 ->
                                    "Retrying (${state.retryAttempt}/${state.maxRetries})…"
                                else -> "Loading stations…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            // First-page load failed (and nothing was previously cached
            // in memory) — show a centered error with a Retry button that
            // spans the full grid width. Reached only after MAX_AUTO_RETRIES
            // attempts in StationListViewModel have all failed.
            else if (state.error != null && state.stations.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ErrorPanel(
                        message = state.error ?: "Couldn't load stations. Check your connection.",
                        onRetry = { stationListViewModel.loadNextPage() }
                    )
                }
            }
            // Either loaded successfully or a refresh failed after items
            // had already landed — render what we have.
            else {
                // Interleave banner ads every AdMobConfig.BANNER_INTERVAL
                // stations, then append a guaranteed end-of-list ad so
                // short lists (e.g. CN network country subsets where Cuba
                // or China only return ~4 stations) still surface at least
                // one ad. The flat list is recomputed per recomposition —
                // fine because typical StationListScreen lists are <100
                // items; building it inside remember{} would break the
                // LazyGridScope (@Composable invocations forbidden here)
                // lambda, so we keep it inline. Stable per-position keys
                // (`ad-banner-station-list-mid-N`) survive pagination
                // without Pager reconciliation churn.
                val rows = state.stations.flatMapIndexed { idx, station ->
                    val stationRow = listOf<Any>(station)
                    if ((idx + 1) % AdMobConfig.BANNER_INTERVAL == 0) {
                        val adNumber = (idx + 1) / AdMobConfig.BANNER_INTERVAL
                        stationRow + listOf<Any>("ad-banner-station-list-mid-$adNumber")
                    } else {
                        stationRow
                    }
                }
                items(
                    items = rows,
                    key = { item ->
                        if (item is String) item
                        else (item as Station).stationuuid
                    },
                    span = { item ->
                        if (item is String) GridItemSpan(maxLineSpan)
                        else GridItemSpan(1)
                    },
                ) { item ->
                    if (item is String) {
                        BannerAd(modifier = Modifier.padding(vertical = 8.dp))
                    } else {
                        val station = item as Station
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
                }

                // Bug fix (browse-countries-china no-ads): append a guaranteed
                // end-of-list banner ad. Stable key prevents Pager
                // reconciliation churn across paginations. Distinct from the
                // mid-list ad keys (`ad-banner-station-list-mid-N`) so the
                // two slots reconcile independently.
                if (state.stations.isNotEmpty()) {
                    bannerAdItem(key = "ad-banner-station-list-end")
                }

                // Pagination footer — spans the full row so it doesn't get
                // squeezed into a single grid cell. Three states:
                //  - loading the next page   → spinner
                //  - last page load failed   → "Retry loading more" button
                //  - more pages available    → "Load next 50 stations" button
                //  - no more pages           → "End of list — N stations total"
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            state.isLoadingMore -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(32.dp),
                                    )
                                    Text(
                                        text = "Loading next page…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                            state.error != null -> {
                                FilledTonalButton(onClick = { stationListViewModel.loadNextPage() }) {
                                    Text("Retry loading more")
                                }
                            }
                            !state.endOfReached -> {
                                FilledTonalButton(onClick = { stationListViewModel.loadNextPage() }) {
                                    Text("Load next 50 stations")
                                }
                            }
                            else -> {
                                Text(
                                    text = "End of list — ${state.stations.size} stations total",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
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
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
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