package me.ghost.ffui.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ghost.ffui.R
import me.ghost.ffui.ui.controls.ControlsScreen
import me.ghost.ffui.ui.dashboard.DashboardScreen
import me.ghost.ffui.ui.discovery.DiscoveryScreen
import me.ghost.ffui.ui.files.FilesScreen
import me.ghost.ffui.ui.info.PrinterInfoScreen
import me.ghost.ffui.ui.settings.PrinterSettingsScreen
import me.ghost.ffui.ui.settings.SettingsScreen
import me.ghost.ffui.ui.spools.SpoolsScreen
import me.ghost.ffui.ui.spools.SpoolEditScreen
import kotlinx.serialization.Serializable

@Serializable
object DashboardRoute

@Serializable
object ControlsRoute

@Serializable
object PrintersRoute

@Serializable
object SettingsRoute

/** Route to the Spools tab — only visible when the Spoolman integration is enabled. */
@Serializable
object SpoolsRoute

/** Route to the spool edit screen. */
@Serializable
data class SpoolEditRoute(val spoolId: Int)

/** Route to the per-printer settings screen; takes the printer's serial as a nav argument. */
@Serializable
data class PrinterSettingsRoute(val serialNumber: String)

/** Route to the file browser / print picker for a printer. */
@Serializable
data class FilesRoute(val serialNumber: String)

/** Route to the read-mostly "what the printer reports" info screen. */
@Serializable
data class PrinterInfoRoute(val serialNumber: String)

@Composable
fun FlasherApp(viewModel: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val spoolmanEnabled by viewModel.settingsDataStore.spoolmanEnabled.collectAsStateWithLifecycle(initialValue = false)
    
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                NavigationBarItem(
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = stringResource(R.string.nav_home)) },
                    label = { Text(stringResource(R.string.nav_home)) },
                    selected = currentDestination?.hierarchy?.any { it.hasRoute(DashboardRoute::class) } == true,
                    onClick = {
                        navController.navigate(DashboardRoute) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Build, contentDescription = stringResource(R.string.controls_title)) },
                    label = { Text(stringResource(R.string.controls_title)) },
                    selected = currentDestination?.hierarchy?.any { it.hasRoute(ControlsRoute::class) } == true,
                    onClick = {
                        navController.navigate(ControlsRoute) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Print, contentDescription = stringResource(R.string.printers_title)) },
                    label = { Text(stringResource(R.string.printers_title)) },
                    selected = currentDestination?.hierarchy?.any { it.hasRoute(PrintersRoute::class) } == true,
                    onClick = {
                        navController.navigate(PrintersRoute) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                if (spoolmanEnabled) {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Album, contentDescription = stringResource(R.string.spools_title)) },
                        label = { Text(stringResource(R.string.spools_title)) },
                        selected = currentDestination?.hierarchy?.any { it.hasRoute(SpoolsRoute::class) } == true,
                        onClick = {
                            navController.navigate(SpoolsRoute) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title)) },
                    label = { Text(stringResource(R.string.settings_title)) },
                    selected = currentDestination?.hierarchy?.any { it.hasRoute(SettingsRoute::class) } == true,
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
                    onNavigateToInfo = { serial ->
                        navController.navigate(PrinterInfoRoute(serial))
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
            
            composable<ControlsRoute> {
                ControlsScreen(viewModel = viewModel)
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

            composable<SpoolsRoute> {
                SpoolsScreen(
                    viewModel = viewModel,
                    onNavigateToEdit = { spoolId ->
                        navController.navigate(SpoolEditRoute(spoolId))
                    },
                    onNavigateToSettings = {
                        navController.navigate(SettingsRoute) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            composable<SpoolEditRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SpoolEditRoute>()
                SpoolEditScreen(
                    spoolId = route.spoolId,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
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

            composable<PrinterInfoRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<PrinterInfoRoute>()
                PrinterInfoScreen(
                    serialNumber = route.serialNumber,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
