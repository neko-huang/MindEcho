package com.moodecho.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.moodecho.app.ui.screens.HistoryScreen
import com.moodecho.app.ui.screens.HomeScreen
import com.moodecho.app.ui.screens.RecordingScreen
import com.moodecho.app.ui.screens.ReportScreen
import com.moodecho.app.ui.screens.SessionDetailScreen
import com.moodecho.app.ui.screens.SettingsScreen
import com.moodecho.app.util.Constants

/**
 * Bottom navigation bar items.
 */
data class BottomNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

/**
 * Main navigation host for MindEcho.
 * Manages navigation between screens using Compose Navigation.
 */
@Composable
fun MindEchoNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Define bottom navigation items
    val bottomNavItems = listOf(
        BottomNavItem(Constants.ROUTE_HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
        BottomNavItem(Constants.ROUTE_HISTORY, "History", Icons.Filled.History, Icons.Outlined.History),
        BottomNavItem(Constants.ROUTE_SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
    )

    // Determine if bottom bar should be visible (hidden during recording)
    val showBottomBar = currentDestination?.route != Constants.ROUTE_RECORDING

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == item.route
                        } == true

                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label) },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    // Pop up to the start destination to avoid building up a stack
                                    popUpTo(Constants.ROUTE_HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Constants.ROUTE_HOME,
            modifier = Modifier.padding(innerPadding)
        ) {
            // Home screen: quick start recording + recent sessions
            composable(Constants.ROUTE_HOME) {
                HomeScreen(
                    onStartRecording = {
                        navController.navigate(Constants.ROUTE_RECORDING)
                    },
                    onSessionClick = { sessionId ->
                        navController.navigate("session/$sessionId")
                    },
                    onReportClick = { date ->
                        navController.navigate("report/$date")
                    }
                )
            }

            // Recording screen: live waveform + emotion detection
            composable(Constants.ROUTE_RECORDING) {
                RecordingScreen(
                    onFinish = { sessionId ->
                        navController.navigate("session/$sessionId") {
                            popUpTo(Constants.ROUTE_HOME) { inclusive = false }
                        }
                    },
                    onCancel = {
                        navController.popBackStack()
                    }
                )
            }

            // History screen: list of past recordings
            composable(Constants.ROUTE_HISTORY) {
                HistoryScreen(
                    onSessionClick = { sessionId ->
                        navController.navigate("session/$sessionId")
                    }
                )
            }

            // Session detail screen: transcript + emotion timeline
            composable(
                route = Constants.ROUTE_SESSION_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getLong("id") ?: return@composable
                SessionDetailScreen(
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() }
                )
            }

            // Report screen: emotion statistics + suggestions
            composable(
                route = Constants.ROUTE_REPORT,
                arguments = listOf(navArgument("date") { type = NavType.StringType })
            ) { backStackEntry ->
                val date = backStackEntry.arguments?.getString("date") ?: return@composable
                ReportScreen(
                    date = date,
                    onBack = { navController.popBackStack() }
                )
            }

            // Settings screen: API config + privacy + about
            composable(Constants.ROUTE_SETTINGS) {
                SettingsScreen()
            }
        }
    }
}
