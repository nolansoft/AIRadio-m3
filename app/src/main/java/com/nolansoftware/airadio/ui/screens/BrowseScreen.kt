// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.nolansoftware.airadio.R
import com.nolansoftware.airadio.ads.bannerAdItem
import com.nolansoftware.airadio.domain.model.SyncState
import com.nolansoftware.airadio.ui.components.BrowseItemCard
import com.nolansoftware.airadio.ui.components.SkeletonBrowseRow
import com.nolansoftware.airadio.ui.components.SyncStatusBanner
import com.nolansoftware.airadio.ui.navigation.Screen
import com.nolansoftware.airadio.ui.viewmodels.BrowseViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    navController: NavController,
    browseViewModel: BrowseViewModel = hiltViewModel()
) {
    val selectedTab by browseViewModel.selectedTab.collectAsState()
    val countries by browseViewModel.countries.collectAsState(initial = emptyList())
    val languages by browseViewModel.languages.collectAsState(initial = emptyList())
    val tags by browseViewModel.tags.collectAsState(initial = emptyList())
    val syncState by browseViewModel.syncState.collectAsState()

    val tabs = listOf(
        TabItem(
            title = stringResource(R.string.countries),
            icon = Icons.Default.Public,
            type = BrowseViewModel.BrowseTab.Countries
        ),
        TabItem(
            title = stringResource(R.string.languages),
            icon = Icons.Default.Translate,
            type = BrowseViewModel.BrowseTab.Languages
        ),
        TabItem(
            title = stringResource(R.string.tags),
            icon = Icons.Default.Tag,
            type = BrowseViewModel.BrowseTab.Tags
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browse)) }
            )
        }
    ) { innerPadding ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            SyncStatusBanner(
                syncState = syncState,
                onRetry = { browseViewModel.retrySync() }
            )

            TabRow(
                selectedTabIndex = tabs.indexOfFirst { it.type == selectedTab },
                modifier = Modifier.fillMaxWidth()
            ) {
                tabs.forEachIndexed { _, tab ->
                    Tab(
                        selected = selectedTab == tab.type,
                        onClick = { browseViewModel.selectTab(tab.type) },
                        text = { Text(tab.title) },
                        icon = { Icon(tab.icon, contentDescription = tab.title) }
                    )
                }
            }

            when (selectedTab) {
                BrowseViewModel.BrowseTab.Countries -> BrowseContent(
                    items = countries.map { it.name to it.stationCount },
                    isLoading = countries.isEmpty() && syncState is SyncState.Syncing,
                    tabIndex = 0,
                    onItemClick = { country ->
                        navController.navigate(
                            Screen.StationList.createRoute("country", country)
                        )
                    }
                )
                BrowseViewModel.BrowseTab.Languages -> BrowseContent(
                    items = languages.map { it.name to it.stationCount },
                    isLoading = languages.isEmpty() && syncState is SyncState.Syncing,
                    tabIndex = 1,
                    onItemClick = { language ->
                        navController.navigate(
                            Screen.StationList.createRoute("language", language)
                        )
                    }
                )
                BrowseViewModel.BrowseTab.Tags -> BrowseContent(
                    items = tags.map { it.name to it.stationCount },
                    isLoading = tags.isEmpty() && syncState is SyncState.Syncing,
                    tabIndex = 2,
                    onItemClick = { tag ->
                        navController.navigate(
                            Screen.StationList.createRoute("tag", tag)
                        )
                    }
                )
            }
        }
    }
}

private const val SKELETON_BROWSE_COUNT = 10

@Composable
private fun BrowseContent(
    items: List<Pair<String, Int>>,
    isLoading: Boolean,
    tabIndex: Int,
    onItemClick: (String) -> Unit
) {
    if (isLoading) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(SKELETON_BROWSE_COUNT) { SkeletonBrowseRow() }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(
                items = items,
                // Pair.first is the country / language / tag name; the API returns
                // unique names within each browse dimension, so it's a stable key
                // for LazyColumn identity tracking.
                key = { it.first }
            ) { (name, count) ->
                BrowseItemCard(
                    name = name,
                    count = count,
                    onClick = { onItemClick(name) }
                )
            }
            bannerAdItem(key = "ad-banner-browse-$tabIndex")
        }
    }
}

private data class TabItem(
    val title: String,
    val icon: ImageVector,
    val type: BrowseViewModel.BrowseTab
)
