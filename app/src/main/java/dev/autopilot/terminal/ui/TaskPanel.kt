package dev.autopilot.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.autopilot.terminal.agent.ChatEntry
import dev.autopilot.terminal.agent.ChatRole

private val RoleColor = mapOf(
    ChatRole.USER to Color.White,
    ChatRole.AI to AccentGreen,
    ChatRole.CMD to Color(0xFFFBBF24),
    ChatRole.OUTPUT to Color(0xFF9CA3AF),
    ChatRole.SYSTEM to AccentAmber
)

@Composable
fun ChatPanel(vm: AutopilotViewModel) {
    val state by vm.engine.uiState.collectAsStateSafe()
    val chat by vm.engine.chat.collectAsStateSafe()
    val listState = rememberLazyListState()

    LaunchedEffect(chat.size) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1)
    }

    Surface {
        Column(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 300.dp).background(TerminalSurface)) {
            when (val s = state) {
                is AgentUiState.Planning -> StatusRow("AI 正在制定执行计划...", s)
                is AgentUiState.Executing -> ExecutingHeader(s, vm)
                is AgentUiState.AwaitConfirm -> ConfirmDialog(s, vm)
                is AgentUiState.PausedLimit -> LimitRow(vm)
                is AgentUiState.Done -> DoneRow(s.summary)
                else -> Unit
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (chat.isEmpty()) {
                    item {
                        Text(
                            "输入任务目标，AI 将在终端中自动完成。所有执行过程在此可见。",
                            color = Color(0xFF6B7280), fontSize = 12.sp
                        )
                    }
                }
                items(chat) { entry -> ChatBubble(entry) }
            }

            GoalInput(vm)
        }
    }
}

@Composable
private fun ChatBubble(entry: ChatEntry) {
    val color = RoleColor[entry.role] ?: Color.Gray
    val mono = entry.role == ChatRole.CMD || entry.role == ChatRole.OUTPUT
    Text(
        text = entry.text.take(1500),
        color = color,
        fontSize = if (entry.role == ChatRole.OUTPUT) 10.sp else 12.sp,
        lineHeight = if (entry.role == ChatRole.OUTPUT) 13.sp else 16.sp,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
    )
}

@Composable
private fun GoalInput(vm: AutopilotViewModel) {
    var goal by remember { mutableStateOf("") }
    var criteria by remember { mutableStateOf("") }
    var showCriteria by remember { mutableStateOf(false) }

    fun sendChat() {
        if (goal.isBlank()) return
        vm.engine.chat(goal.trim())
        goal = ""
    }

    fun submitTask() {
        if (goal.isBlank()) return
        val list = criteria.lines().map { it.trim().filter { c -> c != '\r' } }.filter { it.isNotEmpty() }
        vm.submitTask(goal.trim(), list)
        goal = ""
        criteria = ""
        showCriteria = false
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it },
            placeholder = { Text("对话或下达任务...", fontSize = 12.sp) },
            modifier = Modifier.weight(1f),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color.White),
            minLines = 1,
            maxLines = 2
        )
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { showCriteria = !showCriteria }) { Text("+", fontSize = 14.sp) }
        Spacer(Modifier.width(6.dp))
        Button(onClick = { sendChat() }, enabled = goal.isNotBlank()) { Text("发送") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { submitTask() }, enabled = goal.isNotBlank()) { Text("任务") }
    }

    if (showCriteria) {
        OutlinedTextField(
            value = criteria,
            onValueChange = { criteria = it },
            placeholder = { Text("验收标准（每行一条，配合\"任务\"按钮）", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(70.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White)
        )
    }
}

@Composable
private fun StatusRow(text: String, state: AgentUiState.Planning) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
        LinearProgressIndicator(Modifier.width(80.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color(0xFFD1D5DB), fontSize = 12.sp)
    }
}

@Composable
private fun ExecutingHeader(state: AgentUiState.Executing, vm: AutopilotViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
        LinearProgressIndicator(Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(
            "第 ${state.iteration} 轮 · ${state.stepIndex + 1}/${state.totalSteps}",
            color = AccentGreen, fontSize = 11.sp
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { vm.engine.stop() }) { Text("停止", fontSize = 11.sp) }
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
private fun LimitRow(vm: AutopilotViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text("已达迭代上限", color = AccentAmber, fontSize = 12.sp, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { vm.engine.stop("任务已归档") }) { Text("结束") }
    }
}

@Composable
private fun DoneRow(summary: String) {
    Text(
        "完成: $summary",
        color = AccentGreen, fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 12.dp),
        maxLines = 3
    )
}

@Composable
private fun Surface(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(color = TerminalSurface, tonalElevation = 2.dp) { content() }
}
