package com.nolansoftware.airadio.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.nolansoftware.airadio.R
import com.nolansoftware.airadio.domain.model.PlayerState
import com.nolansoftware.airadio.domain.model.Station
import com.nolansoftware.airadio.domain.model.SyncState
import com.nolansoftware.airadio.ui.components.SkeletonStationCard
import com.nolansoftware.airadio.ui.components.StationCard
import com.nolansoftware.airadio.ui.components.SyncStatusBanner
import com.nolansoftware.airadio.ui.navigation.Screen
import com.nolansoftware.airadio.ui.viewmodels.HomeViewModel
import com.nolansoftware.airadio.ui.viewmodels.PlayerViewModel
import kotlinx.coroutines.delay

private const val SKELETON_COUNT = 6

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
    // and there's no data". The skeleton grid is rendered while the Flow is
    // still null OR while the flow has emitted an empty list and a sync is
    // still in flight, so the saved LazyListState is never asked to anchor
    // against an empty list.
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

            val popularStationsList = popularStations.orEmpty()
            val recentlyPlayedList = recentlyPlayed.orEmpty()
            val localStationsList = localStations.orEmpty()
            // Skeleton stays up while the Flow hasn't emitted yet, OR while a
            // sync is in flight and the Flow has only emitted an empty list.
            val showSkeleton = popularStations == null ||
                (popularStationsList.isEmpty() && syncState is SyncState.Syncing)

            if (showSkeleton) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(count = SKELETON_COUNT) { SkeletonStationCard() }
                }
            } else {
                // Single LazyVerticalGrid as the scrollable container. Earlier
                // versions of this file used an outer LazyColumn with each
                // section's LazyVerticalGrid nested inside an `item {}` —
                // that crashes at first launch with `IllegalStateException:
                // Vertically scrollable component was measured with an infinity
                // maximum height constraints` because nested vertically-scrollable
                // Compose containers are forbidden (the outer LazyColumn gives
                // the inner grid infinity max height, which grids cannot have).
                // The fix is to flatten everything into one grid: section headers
                // and the Recently Played carousel become full-width items via
                // `GridItemSpan(maxLineSpan)`, and the station cards become
                // regular grid cells. Horizontal scrolling inside a full-width
                // grid item works because LazyRow only needs bounded vertical
                // constraints, which it gets from the SectionHeader above it
                // plus its tallest card (aspectRatio(1f) → fixed height).
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (recentlyPlayedList.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                SectionHeader(text = stringResource(R.string.recently_played))
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(
                                        items = recentlyPlayedList,
                                        // Stable key on stationuuid. Without this the
                                        // LazyRow tracks items by index; a re-emission
                                        // of recentlyPlayed (e.g. after addToRecentlyPlayed
                                        // fires from PlayerViewModel) shifts every later
                                        // card and Compose reconciles by re-running the
                                        // entrance animation on cards that didn't change.
                                        key = { it.stationuuid }
                                    ) { station ->
                                        val isFav by homeViewModel.isFavorite(station.stationuuid)
                                            .collectAsState(initial = false)
                                        Box(modifier = Modifier.fillParentMaxWidth(0.42f)) {
                                            AnimatedStationCardItem(
                                                index = recentlyPlayedList.indexOf(station),
                                                station = station,
                                                isFavorite = isFav,
                                                navController = navController,
                                                playerViewModel = playerViewModel,
                                                playerState = playerState,
                                                homeViewModel = homeViewModel,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (popularStationsList.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(text = stringResource(R.string.popular_stations))
                        }
                        items(
                            items = popularStationsList,
                            // See the recentlyPlayed key comment above.
                            // popularStations is the section the user is most
                            // likely to scroll deep into; losing the anchor on
                            // return is what made this bug visible.
                            key = { it.stationuuid }
                        ) { station ->
                            val isFav by homeViewModel.isFavorite(station.stationuuid)
                                .collectAsState(initial = false)
                            AnimatedStationCardItem(
                                index = popularStationsList.indexOf(station),
                                station = station,
                                isFavorite = isFav,
                                navController = navController,
                                playerViewModel = playerViewModel,
                                playerState = playerState,
                                homeViewModel = homeViewModel,
                            )
                        }
                    }

                    if (localStationsList.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(text = stringResource(R.string.local_stations))
                        }
                        val locals = localStationsList.take(10)
                        items(
                            items = locals,
                            key = { it.stationuuid }
                        ) { station ->
                            val isFav by homeViewModel.isFavorite(station.stationuuid)
                                .collectAsState(initial = false)
                            AnimatedStationCardItem(
                                index = locals.indexOf(station),
                                station = station,
                                isFavorite = isFav,
                                navController = navController,
                                playerViewModel = playerViewModel,
                                playerState = playerState,
                                homeViewModel = homeViewModel,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 8.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AnimatedStationCardItem(
    index: Int,
    station: Station,
    isFavorite: Boolean,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    playerState: PlayerState,
    homeViewModel: HomeViewModel,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(station.stationuuid) {
        delay(index * 30L)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) +
                slideInVertically(initialOffsetY = { it / 10 }, animationSpec = tween(180)),
        exit = fadeOut(),
    ) {
        StationCard(
            station = station,
            isFavorite = isFavorite,
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