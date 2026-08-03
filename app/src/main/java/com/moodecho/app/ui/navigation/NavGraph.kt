package com.moodecho.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.moodecho.app.ui.screens.ReportTabScreen
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
 * @param hasAllPermissions Whether all required runtime permissions have been granted
 * @param onRequestPermissions Callback to trigger the permission request flow
 */
@Composable
fun MindEchoNavHost(
    hasAllPermissions: Boolean,
    onRequestPermissions: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Track whether we should auto-navigate to recording after permission grant
    var pendingNavigationToRecording by remember { mutableStateOf(false) }

    // Auto-navigate to recording when permissions are granted and we were waiting
    LaunchedEffect(hasAllPermissions) {
        if (hasAllPermissions && pendingNavigationToRecording) {
            pendingNavigationToRecording = false
            navController.navigate(Constants.ROUTE_RECORDING)
        }
    }

    // Define bottom navigation items
    val bottomNavItems = listOf(
        BottomNavItem(Constants.ROUTE_HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
        BottomNavItem(Constants.ROUTE_HISTORY, "History", Icons.Filled.History, Icons.Outlined.History),
        BottomNavItem(Constants.ROUTE_REPORT_TAB, "Report", Icons.Filled.Assessment, Icons.Outlined.Assessment),
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
                    hasAllPermissions = hasAllPermissions,
                    onStartRecording = {
                        if (hasAllPermissions) {
                            // Permissions already granted, navigate to recording
                            navController.navigate(Constants.ROUTE_RECORDING)
                        } else {
                            // Request permissions first; auto-navigate after grant
                            pendingNavigationToRecording = true
                            onRequestPermissions()
                        }
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

            // Report tab screen: daily report with generate button
            composable(Constants.ROUTE_REPORT_TAB) {
                ReportTabScreen(
                    onReportClick = { date ->
                        navController.navigate("report/$date")
                    },
                    onNavigateToSettings = {
                        navController.navigate(Constants.ROUTE_SETTINGS)
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

            // Report screen for a specific date: emotion statistics + suggestions
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
