package com.nolansoftware.airadio.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.ui.graphics.vector.ImageVector
import com.nolansoftware.airadio.R

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    object Home : Screen(
        route = "home",
        title = "Home",
        icon = Icons.Default.Home
    )

    object Search : Screen(
        route = "search",
        title = "Search",
        icon = Icons.Default.Search
    )

    object Browse : Screen(
        route = "browse",
        title = "Browse",
        icon = Icons.Outlined.Apps
    )

    object Favorites : Screen(
        route = "favorites",
        title = "Favorites",
        icon = Icons.Default.Favorite
    )

    object Player : Screen(
        route = "player/{stationId}",
        title = "Player",
        icon = Icons.Default.Home
    ) {
        fun createRoute(stationId: String) = "player/$stationId"
    }

    object StationList : Screen(
        route = "station_list/{type}/{query}",
        title = "Stations",
        icon = Icons.Default.Home
    ) {
        fun createRoute(type: String, query: String) = "station_list/$type/$query"
    }
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.Search,
    Screen.Browse,
    Screen.Favorites
)