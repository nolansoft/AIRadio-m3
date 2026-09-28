// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val iconOutlined: ImageVector,
    val iconFilled: ImageVector,
) {
    object Home : Screen("home", "Home", Icons.Outlined.Home, Icons.Filled.Home)
    object Search : Screen("search", "Search", Icons.Outlined.Search, Icons.Filled.Search)
    object Browse : Screen("browse", "Browse", Icons.Outlined.Apps, Icons.Filled.Apps)
    object Favorites : Screen("favorites", "Favorites", Icons.Outlined.FavoriteBorder, Icons.Filled.Favorite)

    object Player : Screen("player/{stationId}", "Player", Icons.Outlined.Home, Icons.Filled.Home) {
        fun createRoute(stationId: String) = "player/$stationId"
    }

    object StationList : Screen("station_list/{type}/{query}", "Stations", Icons.Outlined.Home, Icons.Filled.Home) {
        fun createRoute(type: String, query: String) = "station_list/$type/$query"
    }
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.Search,
    Screen.Browse,
    Screen.Favorites,
)