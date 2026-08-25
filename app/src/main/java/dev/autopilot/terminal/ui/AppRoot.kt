package dev.autopilot.terminal.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.autopilot.terminal.ui.files.FileTreeScreen
import dev.autopilot.terminal.ui.settings.ModelSettingsScreen

sealed class Dest(val route: String, val label: String, val icon: @Composable () -> Unit) {
    data object Terminal : Dest("terminal", "终端", { Icon(Icons.Filled.Terminal, null) })
    data object Files : Dest("files", "文件", { Icon(Icons.Filled.Description, null) })
    data object Settings : Dest("settings", "设置", { Icon(Icons.Filled.Settings, null) })
}

@Composable
fun AppRoot(vm: AutopilotViewModel = viewModel()) {
    val riskAccepted by vm.riskAccepted.collectAsStateSafe()
    AutopilotTheme {
        if (!riskAccepted) {
            RiskOnboarding(onAccept = vm::acceptRisk)
            return@AutopilotTheme
        }
        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        val currentRoute = backStack?.destination?.route ?: Dest.Terminal.route

        val navTarget by vm.navigateTab.collectAsStateSafe()
        LaunchedEffect(navTarget) {
            val target = navTarget ?: return@LaunchedEffect
            runCatching {
                navController.navigate(target) {
                    popUpTo(Dest.Terminal.route) { saveState = true }
                    launchSingleTop = true
                }
            }
            vm.navigateTab.value = null
        }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    listOf(Dest.Terminal, Dest.Files, Dest.Settings).forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(Dest.Terminal.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = dest.icon,
                            label = { Text(dest.label) }
                        )
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Dest.Terminal.route,
                modifier = Modifier.padding(padding)
            ) {
                composable(Dest.Terminal.route) { TerminalScreen(vm) }
                composable(Dest.Files.route) { FileTreeScreen(vm) }
                composable(Dest.Settings.route) { ModelSettingsScreen(vm) }
            }
        }
    }
}
