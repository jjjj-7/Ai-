package dev.autopilot.terminal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.autopilot.terminal.ui.files.FileTreeScreen
import dev.autopilot.terminal.ui.settings.ModelSettingsScreen

sealed class Dest(val route: String, val label: String, val icon: @Composable () -> Unit) {
    data object Chat : Dest("chat", "对话", { Icon(Icons.Filled.Chat, null) })
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
        var showBoot by remember { mutableStateOf(true) }
        Box(Modifier.fillMaxSize()) {
            InnerNavHost(vm)
            if (showBoot) BootOverlay(onDone = { showBoot = false })
        }
    }
}

@Composable
private fun InnerNavHost(vm: AutopilotViewModel) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Dest.Chat.route

    val navTarget by vm.navigateTab.collectAsStateSafe()
    LaunchedEffect(navTarget) {
        val target = navTarget ?: return@LaunchedEffect
        runCatching {
            navController.navigate(target) {
                popUpTo(Dest.Chat.route) { saveState = true }
                launchSingleTop = true
            }
        }
        vm.navigateTab.value = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf(Dest.Chat, Dest.Files, Dest.Settings).forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(Dest.Chat.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = dest.icon,
                        label = { Text(dest.label, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Chat.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Dest.Chat.route) { ChatScreen(vm) }
            composable(Dest.Files.route) { FileTreeScreen(vm) }
            composable(Dest.Settings.route) { ModelSettingsScreen(vm) }
        }
    }
}
