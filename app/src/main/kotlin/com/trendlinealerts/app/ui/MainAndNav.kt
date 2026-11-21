package com.trendlinealerts.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trendlinealerts.app.TrendlineAlertsApp
import com.trendlinealerts.app.di.AppContainer
import com.trendlinealerts.app.service.MonitoringForegroundService
import com.trendlinealerts.app.ui.alertlog.AlertLogScreen
import com.trendlinealerts.app.ui.alertlog.AlertLogViewModel
import com.trendlinealerts.app.ui.debug.DebugScreen
import com.trendlinealerts.app.ui.debug.DebugViewModel
import com.trendlinealerts.app.ui.monitor.MonitorScreen
import com.trendlinealerts.app.ui.monitor.MonitorViewModel
import com.trendlinealerts.app.ui.settings.BatteryOptimizationScreen
import com.trendlinealerts.app.ui.settings.SettingsScreen
import com.trendlinealerts.app.ui.settings.SettingsViewModel
import com.trendlinealerts.app.ui.theme.TrendlineAlertsTheme
import com.trendlinealerts.app.ui.watchlist.WatchlistScreen
import com.trendlinealerts.app.ui.watchlist.WatchlistViewModel

object Routes {
    const val MONITOR = "monitor"
    const val WATCHLIST = "watchlist"
    const val ALERT_LOG = "alert_log"
    const val SETTINGS = "settings"
    const val DEBUG = "debug/{symbol}"
    const val BATTERY_HELPER = "battery_helper"

    fun debugFor(symbol: String): String = "debug/$symbol"
}

private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val BOTTOM_NAV_ITEMS = listOf(
    BottomNavItem(Routes.MONITOR, "Monitor", Icons.Filled.MonitorHeart),
    BottomNavItem(Routes.WATCHLIST, "Watchlist", Icons.Filled.List),
    BottomNavItem(Routes.ALERT_LOG, "Alerts", Icons.Filled.History),
    BottomNavItem(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
    BottomNavItem(Routes.debugFor("BTCUSDT"), "Debug", Icons.Filled.BugReport)
)

@Composable
fun AppNavGraph(
    container: AppContainer,
    openAlertLogSignal: Int,
    onToggleMonitoring: (start: Boolean) -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(openAlertLogSignal) {
        if (openAlertLogSignal > 0) {
            navController.navigate(Routes.ALERT_LOG) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (currentRoute != Routes.BATTERY_HELPER) {
                NavigationBar {
                    BOTTOM_NAV_ITEMS.forEach { item ->
                        val selected = when {
                            item.route.startsWith("debug/") -> currentRoute?.startsWith("debug/") == true
                            else -> currentRoute == item.route
                        }
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.MONITOR,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.MONITOR) {
                val vm: MonitorViewModel = viewModel(
                    factory = MonitorViewModel.Factory(
                        container.monitorStateRepository,
                        container.watchlistRepository,
                        container.settingsDataStore,
                        container.adaptiveMarketDataSource
                    )
                )
                MonitorScreen(
                    viewModel = vm,
                    onToggleMonitoring = onToggleMonitoring,
                    onOpenSymbolDebug = { symbol ->
                        navController.navigate(Routes.debugFor(symbol))
                    }
                )
            }

            composable(Routes.WATCHLIST) {
                val vm: WatchlistViewModel = viewModel(
                    factory = WatchlistViewModel.Factory(container.watchlistRepository)
                )
                WatchlistScreen(viewModel = vm)
            }

            composable(Routes.ALERT_LOG) {
                val vm: AlertLogViewModel = viewModel(
                    factory = AlertLogViewModel.Factory(container.alertLogRepository)
                )
                AlertLogScreen(viewModel = vm)
            }

            composable(Routes.SETTINGS) {
                val vm: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(
                        container.settingsDataStore,
                        container.adaptiveMarketDataSource,
                        container.htfCandleCache,
                        container.notificationHelper
                    )
                )
                SettingsScreen(
                    viewModel = vm,
                    onRequestTestNotification = onRequestNotificationPermission,
                    onOpenBatteryHelper = {
                        navController.navigate(Routes.BATTERY_HELPER)
                    }
                )
            }

            composable(
                route = Routes.DEBUG,
                arguments = listOf(navArgument("symbol") { type = NavType.StringType })
            ) { entry ->
                val initialSym = entry.arguments?.getString("symbol") ?: "BTCUSDT"
                val vm: DebugViewModel = viewModel(
                    key = "debug_$initialSym",
                    factory = DebugViewModel.Factory(
                        initialSym,
                        container.monitorStateRepository,
                        container.watchlistRepository
                    )
                )
                DebugScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.BATTERY_HELPER) {
                BatteryOptimizationScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    private var openAlertLogSignal by mutableIntStateOf(0)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        val container = (application as TrendlineAlertsApp).container
        requestPostNotificationsIfNeeded()

        setContent {
            TrendlineAlertsTheme {
                AppNavGraph(
                    container = container,
                    openAlertLogSignal = openAlertLogSignal,
                    onToggleMonitoring = { start ->
                        if (start) {
                            requestPostNotificationsIfNeeded()
                            MonitoringForegroundService.start(this)
                        } else {
                            MonitoringForegroundService.stop(this)
                        }
                    },
                    onRequestNotificationPermission = {
                        requestPostNotificationsIfNeeded()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN_TAB) == TAB_ALERT_LOG) {
            openAlertLogSignal++
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val helper = (application as TrendlineAlertsApp).container.notificationHelper
            if (!helper.hasPostNotificationPermission()) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "extra_open_tab"
        const val TAB_ALERT_LOG = "alert_log"
    }
}