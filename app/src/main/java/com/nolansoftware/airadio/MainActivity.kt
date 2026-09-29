// SPDX-License-Identifier: Apache-2.0

package com.nolansoftware.airadio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nolansoftware.airadio.consent.ConsentManager
import com.nolansoftware.airadio.ui.navigation.Screen
import com.nolansoftware.airadio.ui.navigation.bottomNavItems
import com.nolansoftware.airadio.ui.screens.BrowseScreen
import com.nolansoftware.airadio.ui.screens.FavoritesScreen
import com.nolansoftware.airadio.ui.screens.HomeScreen
import com.nolansoftware.airadio.ui.screens.PlayerScreen
import com.nolansoftware.airadio.ui.screens.SearchScreen
import com.nolansoftware.airadio.ui.screens.StationListScreen
import com.nolansoftware.airadio.ui.theme.AIRadioTheme
import com.nolansoftware.airadio.ui.viewmodels.PlayerViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var consentManager: ConsentManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AIRadioTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainApp(consentManager = consentManager)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(consentManager: ConsentManager) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val activity = LocalContext.current as ComponentActivity

    val showBottomBar = bottomNavItems.any { screen ->
        currentDestination?.hierarchy?.any { it.route == screen.route } == true
    }

    LaunchedEffect(Unit) {
        consentManager.requestConsentFormIfNeeded(activity) { /* ready */ }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { screen ->
                        val isSelected = currentDestination?.hierarchy?.any {
                            it.route == screen.route
                        } == true
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = if (isSelected) screen.iconFilled else screen.iconOutlined,
                                    contentDescription = screen.title
                                )
                            },
                            label = { Text(screen.title, style = MaterialTheme.typography.labelMedium) },
                            selected = isSelected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    navController = navController,
                    playerViewModel = playerViewModel
                )
            }
            composable(Screen.Search.route) {
                SearchScreen(
                    navController = navController,
                    playerViewModel = playerViewModel,
                    consentManager = consentManager
                )
            }
            composable(Screen.Browse.route) {
                BrowseScreen(navController = navController)
            }
            composable(Screen.Favorites.route) {
                FavoritesScreen(
                    navController = navController,
                    playerViewModel = playerViewModel
                )
            }
            composable(
                route = Screen.Player.route,
                arguments = listOf(navArgument("stationId") { type = NavType.StringType })
            ) { backStackEntry ->
                val stationId = backStackEntry.arguments?.getString("stationId") ?: ""
                PlayerScreen(
                    onBack = { navController.popBackStack() },
                    stationId = stationId,
                    viewModel = playerViewModel
                )
            }
            composable(
                route = Screen.StationList.route,
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("query") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val type = backStackEntry.arguments?.getString("type") ?: ""
                val query = backStackEntry.arguments?.getString("query") ?: ""
                StationListScreen(
                    navController = navController,
                    type = type,
                    query = query,
                    playerViewModel = playerViewModel
                )
            }
        }
    }
}