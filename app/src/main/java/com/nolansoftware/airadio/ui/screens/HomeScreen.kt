// SPDX-License-Identifier: Apache-2.0

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.nolansoftware.airadio.R
import com.nolansoftware.airadio.ads.bannerAdItem
import com.nolansoftware.airadio.consent.ConsentManager
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
    playerViewModel: PlayerViewModel = hiltViewModel(),
    consentManager: ConsentManager
) {
    // The three Room-backed lists are read with `collectAsState()` against
    // `StateFlow<List<Station>?>` properties on HomeViewModel (see the comment
    // in HomeViewModel for why StateFlow rather than cold Flow). The crucial
    // property: when HomeScreen is re-composed after returning from Player,
    // StateFlow.value is read synchronously — no producer-restart window with
    // initialValue=null. Without that, the grid's MeasurePolicy would clamp the
    // saved `firstVisibleItemIndex` against the smaller skeleton item count
    // (6) and mutate the state to a clamped value, losing the user's scroll
    // position. StateFlow's retained value keeps the grid's item count stable
    // across the composition recreation so the saved index anchors correctly.
    val popularStations by homeViewModel.popularStations.collectAsState()
    val recentlyPlayed by homeViewModel.recentlyPlayedStations.collectAsState()
    val localStations by homeViewModel.localStations.collectAsState()
    val playerState by playerViewModel.playerState.observeAsState(PlayerState.Idle)
    val syncState by homeViewModel.syncState.collectAsState()

    // Privacy / consent controls (Google User Messaging Platform). The gear icon
    // used to live on SearchScreen, but Search is a per-task input surface —
    // consent is a global, low-frequency setting. Home is the most-visited
    // destination and Material 3 puts single app-level actions in the TopAppBar
    // `actions = { }` slot, so this is the conventional spot.
    var showPrivacySheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { showPrivacySheet = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.privacy_settings_title),
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        if (showPrivacySheet) {
            ModalBottomSheet(onDismissRequest = { showPrivacySheet = false }) {
                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Text(
                        text = stringResource(R.string.privacy_settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    TextButton(onClick = {
                        consentManager.showPrivacyOptions(context as android.app.Activity) {
                            showPrivacySheet = false
                        }
                    }) {
                        Text(stringResource(R.string.manage_privacy_options))
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        // BLOCKER-A per plan §4 risk #5: replaced by user before M6.
                        uriHandler.openUri("https://example.com/airadio-privacy")
                        showPrivacySheet = false
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
            SyncStatusBanner(
                syncState = syncState,
                onRetry = { homeViewModel.retrySync() }
            )

            val popularStationsList = popularStations.orEmpty()
            val recentlyPlayedList = recentlyPlayed.orEmpty()
            val localStationsList = localStations.orEmpty()
            // The grid is ALWAYS composed — skeleton items vs real items are
            // picked INSIDE the grid's `items { }` block. This keeps the
            // LazyVerticalGrid at a single stable position in the composition
            // tree, so its `rememberLazyGridState()` (which uses rememberSaveable
            // under the hood) survives Player → back navigation. The previous
            // structure conditionally swapped between two `LazyVerticalGrid`s
            // (skeleton vs data), which changed the grid's composition-position
            // key between frames; the saved `LazyGridState.firstVisibleItemIndex`
            // could not anchor against the new grid and was silently clamped
            // to 0, losing the user's scroll position on return.
            //
            // The skeleton items rendered here share the LazyVerticalGrid's
            // position with the real items that replace them, so the saved
            // scroll index anchors against skeleton tiles during initial load
            // and re-anchors against the matching real tile once the Flow
            // emits — no scroll-position jump.
            val isInitialLoad = popularStations == null ||
                (popularStationsList.isEmpty() && syncState is SyncState.Syncing)

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
                if (isInitialLoad) {
                    // Skeleton tiles occupy the SAME grid position as the real
                    // items will. The grid's LazyGridState anchors against
                    // these tiles, and the saved scroll index rides across the
                    // skeleton→data swap because the LazyVerticalGrid's
                    // composition-position key is unchanged.
                    items(count = SKELETON_COUNT) { SkeletonStationCard() }
                } else {
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
                        bannerAdItem(key = "ad-banner-home")
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
    // rememberSaveable (not plain remember) for `visible` — the entrance animation
    // depends on each card starting collapsed and expanding after `index * 30L` ms.
    // With plain `remember`, Player → back navigation rebuilds the composition and
    // resets every card to `visible = false`, which collapses every StationCard via
    // AnimatedVisibility. The outer LazyVerticalGrid then measures a near-zero-height
    // grid; its saved `firstVisibleItemIndex` cannot anchor against the collapsed
    // items, and once the cards re-expand on staggered delays, the saved scroll
    // position is already lost. rememberSaveable keyed by station.stationuuid
    // persists `visible = true` for any card that finished its entrance animation
    // before navigation, so on return those cards stay visible (full height) from
    // the very first frame — the grid measures correctly and the saved index
    // anchors to the right item.
    //
    // The LaunchedEffect's delay is still applied on first composition of a card,
    // but for cards that were visible before navigation, `visible` is restored
    // to true before the LaunchedEffect fires, so the delay's `visible = true`
    // assignment is a no-op (Compose sees no state transition → no re-animation).
    val visibleState = rememberSaveable(
        station.stationuuid,
        stateSaver = autoSaver(),
    ) { mutableStateOf(false) }
    var visible by visibleState
    LaunchedEffect(station.stationuuid) {
        if (!visible) {
            delay(index * 30L)
            visible = true
        }
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