package com.chymaster.octopusagiledashboard.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.chymaster.octopusagiledashboard.data.prefs.UserPreferencesRepository
import com.chymaster.octopusagiledashboard.ui.dashboard.DashboardScreen
import com.chymaster.octopusagiledashboard.ui.future.FuturePricesScreen
import com.chymaster.octopusagiledashboard.ui.home.HomeScreen
import com.chymaster.octopusagiledashboard.ui.settings.AdvancedSettingsScreen
import com.chymaster.octopusagiledashboard.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavGraph(
    preferencesRepository: UserPreferencesRepository,
    navController: NavHostController
) {
    val hasCredentials by preferencesRepository.hasCredentials.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    ModalNavigationDrawer(
        drawerState = drawerState,
s        gesturesEnabled = true,
        drawerContent = {
            DrawerContent(
                currentRoute = currentRoute,
                hasCredentials = hasCredentials,
                onNavigate = { route ->
                    navController.navigate(route) {
                        // Avoid stacking duplicate destinations
                        launchSingleTop = true
                    }
                },
                onCloseDrawer = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(navController = navController, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onOpenDrawer = { scope.launch { drawerState.open() } }
                    )
                }
                composable(Routes.DASHBOARD) {
                    DashboardScreen(
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenFuturePrices = { navController.navigate(Routes.FUTURE_PRICES) },
                        onOpenDrawer = { scope.launch { drawerState.open() } }
                    )
                }
                composable(Routes.FUTURE_PRICES) {
                    FuturePricesScreen(
                        onBack = { navController.popBackStack() },
                        onOpenDrawer = { scope.launch { drawerState.open() } }
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onNavigateBack = { navController.popBackStack() },
                        showBackButton = true,
                        onNavigateToAdvancedSettings = { navController.navigate(Routes.ADVANCED_SETTINGS) }
                    )
                }
                composable(Routes.ADVANCED_SETTINGS) {
                    AdvancedSettingsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
