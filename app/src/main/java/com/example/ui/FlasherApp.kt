package com.example.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ui.dashboard.DashboardScreen
import com.example.ui.discovery.DiscoveryScreen
import com.example.ui.files.FilesScreen
import com.example.ui.settings.PrinterSettingsScreen
import com.example.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
object DashboardRoute

@Serializable
object PrintersRoute

@Serializable
object SettingsRoute

/** Route to the per-printer settings screen; takes the printer's serial as a nav argument. */
@Serializable
data class PrinterSettingsRoute(val serialNumber: String)

/** Route to the file browser / print picker for a printer. */
@Serializable
data class FilesRoute(val serialNumber: String)

@Composable
fun FlasherApp(viewModel: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                NavigationBarItem(
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") },
                    selected = currentDestination?.hierarchy?.any { it.route?.contains("DashboardRoute") == true } == true,
                    onClick = {
                        navController.navigate(DashboardRoute) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Print, contentDescription = "Printers") },
                    label = { Text("Printers") },
                    selected = currentDestination?.hierarchy?.any { it.route?.contains("PrintersRoute") == true } == true,
                    onClick = {
                        navController.navigate(PrintersRoute) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    selected = currentDestination?.hierarchy?.any { it.route?.contains("SettingsRoute") == true } == true,
                    onClick = {
                        navController.navigate(SettingsRoute) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = DashboardRoute,
            // Reserve space for the bottom nav + status bar here, then mark those insets as
            // consumed so each screen's own TopAppBar doesn't add the status-bar inset a second
            // time (which made every top bar ~2x too tall).
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            composable<DashboardRoute> {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToSettings = { serial ->
                        navController.navigate(PrinterSettingsRoute(serial))
                    },
                    onNavigateToFiles = { serial ->
                        navController.navigate(FilesRoute(serial))
                    },
                    onNavigateToPrinters = {
                        navController.navigate(PrintersRoute) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            
            composable<PrintersRoute> {
                DiscoveryScreen(
                    viewModel = viewModel,
                    onNavigateToDashboard = {
                        navController.navigate(DashboardRoute) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToSettings = { serial ->
                        navController.navigate(PrinterSettingsRoute(serial))
                    }
                )
            }

            composable<SettingsRoute> {
                SettingsScreen(viewModel = viewModel)
            }

            composable<PrinterSettingsRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<PrinterSettingsRoute>()
                PrinterSettingsScreen(
                    serialNumber = route.serialNumber,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable<FilesRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<FilesRoute>()
                FilesScreen(
                    serialNumber = route.serialNumber,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
