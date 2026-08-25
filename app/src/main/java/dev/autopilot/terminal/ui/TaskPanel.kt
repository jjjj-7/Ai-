package dev.autopilot.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autopilot.terminal.agent.AgentUiState

@Composable
fun TaskPanel(state: AgentUiState, vm: AutopilotViewModel) {
    Surface(color = TerminalSurface, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            when (state) {
                is AgentUiState.Idle -> GoalInput(vm)
                is AgentUiState.Planning -> StatusRow("AI 正在制定执行计划...")
                is AgentUiState.Executing -> ExecutingPanel(state, vm)
                is AgentUiState.AwaitConfirm -> ConfirmDialog(state, vm)
                is AgentUiState.PausedLimit -> LimitPanel(vm)
                is AgentUiState.Done -> ReportPanel(state.summary, state.changedFiles, state.elapsedMs, state.degraded)
                is AgentUiState.Stopped -> StoppedPanel(state.message, vm)
                is AgentUiState.Failed -> FailedPanel(state.reason, vm)
            }
        }
    }
}

@Composable
private fun GoalInput(vm: AutopilotViewModel) {
    var goal by remember { mutableStateOf("") }
    var criteria by remember { mutableStateOf("") }

    fun submit() {
        if (goal.isBlank()) return
        val list = criteria.lines().map { it.trim() }.filter { it.isNotEmpty() }
        vm.submitTask(goal.trim(), list)
        goal = ""
        criteria = ""
    }

    OutlinedTextField(
        value = goal,
        onValueChange = { goal = it },
        placeholder = { Text("描述你的任务目标，例如: 用 Python 写一个 TODO CLI 并自测", fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Color.White),
        minLines = 1,
        maxLines = 3
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = criteria,
        onValueChange = { criteria = it },
        placeholder = { Text("验收标准(可选，每行一条)", fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color.White),
        minLines = 1,
        maxLines = 2
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "通道: ${vm.perms.channelDescription()}",
            color = Color(0xFF9CA3AF),
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
        Button(onClick = { submit() }, enabled = goal.isNotBlank()) {
            Text("启动自动驾驶")
        }
    }
}

@Composable
private fun StatusRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(Modifier.width(80.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color(0xFFD1D5DB), fontSize = 13.sp)
    }
}

@Composable
private fun ExecutingPanel(state: AgentUiState.Executing, vm: AutopilotViewModel) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Text("第 ${state.iteration} 轮 · 步骤 ${state.stepIndex + 1}/${state.totalSteps}", color = AccentGreen, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.engine.stop() }) { Text("停止") }
        }
        if (state.command.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "\$ ${state.command.take(120)}",
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF9CA3AF),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ConfirmDialog(state: AgentUiState.AwaitConfirm, vm: AutopilotViewModel) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("高危命令确认") },
        text = {
            Column {
                Text("原因: ${state.reason}", color = AccentAmber, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text("\$ ${state.command}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        },
        confirmButton = { Button(onClick = vm.engine::confirm) { Text("放行") } },
        dismissButton = { OutlinedButton(onClick = vm.engine::reject) { Text("拒绝") } }
    )
}

@Composable
private fun LimitPanel(vm: AutopilotViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text("已达到自动迭代轮数上限，任务暂停。", color = AccentAmber, fontSize = 13.sp, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { vm.engine.stop("任务已归档") }) { Text("结束") }
    }
}

@Composable
private fun ReportPanel(summary: String, files: List<String>, elapsedMs: Long, degraded: Boolean) {
    Column {
        Text("任务完成", color = AccentGreen, fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text(summary, color = Color(0xFFE5E5E5), fontSize = 13.sp)
        if (files.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("变更文件:", color = Color(0xFF9CA3AF), fontSize = 12.sp)
            files.forEach { f ->
                Text("  $f", color = AccentPurple, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        val degradeNote = if (degraded) " (沙箱级通道)" else ""
        Text("耗时 ${elapsedMs / 1000}s$degradeNote", color = Color(0xFF9CA3AF), fontSize = 11.sp)
    }
}

@Composable
private fun StoppedPanel(message: String, vm: AutopilotViewModel) {
    Column {
        Text(message, color = AccentAmber, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Button(onClick = { vm.engineReset() }) { Text("新任务") }
    }
}

@Composable
private fun FailedPanel(reason: String, vm: AutopilotViewModel) {
    Column {
        Text("失败: $reason", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Button(onClick = { vm.engineReset() }) { Text("重新开始") }
    }
}
