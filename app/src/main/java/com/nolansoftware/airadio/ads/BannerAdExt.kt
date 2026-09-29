// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ads

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Single-source-of-truth LazyGridScope extension for inserting a full-width
 * banner ad cell. Stable key prevents Pager reconciliation churn. The 8.dp
 * vertical padding is the visual separator every banner cell uses across
 * HomeScreen, BrowseScreen, FavoritesScreen, StationListScreen.
 */
fun LazyGridScope.bannerAdItem(key: String, modifier: Modifier = Modifier) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
        BannerAd(modifier = modifier.padding(vertical = 8.dp))
    }
}

/**
 * LazyListScope variant for BrowseScreen (which uses LazyColumn, not
 * LazyVerticalGrid). Identical visual treatment to the grid version.
 */
fun LazyListScope.bannerAdItem(key: String, modifier: Modifier = Modifier) {
    item(key = key) {
        BannerAd(modifier = modifier.padding(vertical = 8.dp))
    }
}