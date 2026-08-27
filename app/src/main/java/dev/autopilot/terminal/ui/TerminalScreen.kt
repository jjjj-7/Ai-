package dev.autopilot.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ChatScreen(vm: AutopilotViewModel) {
    Box(Modifier.fillMaxSize().background(WinBg)) {
        ChatPanel(vm, Modifier.fillMaxSize())
    }
}
