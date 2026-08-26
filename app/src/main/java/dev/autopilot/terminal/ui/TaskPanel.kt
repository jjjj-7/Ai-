package dev.autopilot.terminal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.itemsIndexed
import dev.autopilot.terminal.agent.AgentUiState
import dev.autopilot.terminal.agent.ChatEntry
import dev.autopilot.terminal.agent.ChatRole
import kotlinx.coroutines.delay

private val WinBg = Color(0xFF07070D)
private val WinSurface = Color(0xFF10101C)
private val WinBorder = Color(0xFF1F2436)
private val TextMain = Color(0xFFE5E7EB)
private val TextDim = Color(0xFF9CA3AF)
private val DotR = Color(0xFFFF5F57)
private val DotY = Color(0xFFFEBC2E)
private val DotG = Color(0xFF28C840)
private val Cyan = Color(0xFF22D3EE)

private data class Skill(val label: String, val prompt: String)

private val skills = listOf(
    Skill("联网搜索", "联网搜索今天的科技新闻，总结成要点"),
    Skill("设备体检", "查看设备状态: uname -a、df -h 磁盘占用、free 内存、运行时间"),
    Skill("我的下载", "列出 ~/storage/downloads 目录的内容并逐个说明用途"),
    Skill("写个脚本", "在工作区写一个 python 猜数字小游戏并运行验证"),
    Skill("清理空间", "清理 apt 与 pip 缓存，报告释放了多少空间")
)

@Composable
fun ChatPanel(vm: AutopilotViewModel, modifier: Modifier = Modifier) {
    val state by vm.engine.uiState.collectAsStateSafe()
    val chat by vm.engine.chat.collectAsStateSafe()
    val busy by vm.engine.busy.collectAsStateSafe()
    val listState = rememberLazyListState()

    LaunchedEffect(chat.size) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1)
    }

    Box(modifier.background(WinBg)) {
        Column(Modifier.fillMaxSize()) {
            WindowTitleBar(busy, onStop = { vm.engine.stop() })

            Box(Modifier.fillMaxWidth().height(2.dp).background(
                Brush.horizontalGradient(listOf(AccentGreen, AccentPurple, Cyan))
            ))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (chat.isEmpty()) item { WelcomeBlock() }
                itemsIndexed(chat) { idx, entry ->
                    val isLast = idx == chat.lastIndex
                    TerminalMessage(entry, animate = isLast && entry.role == ChatRole.AI && !busy)
                }
            }

            when (val s = state) {
                is AgentUiState.AwaitConfirm -> ConfirmDialog(s, vm)
                else -> Unit
            }

            SkillChips(enabled = !busy) { vm.engine.chat(it) }

            GoalInput(vm, busy)
        }
        ScanlineOverlay()
    }
}

@Composable
private fun ScanlineOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val step = 4.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = Color.Black.copy(alpha = 0.10f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
            y += step
        }
    }
}

@Composable
private fun WindowTitleBar(busy: Boolean, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(WinSurface).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(DotR))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(9.dp).clip(CircleShape).background(DotY))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(9.dp).clip(CircleShape).background(DotG))
        Spacer(Modifier.width(12.dp))
        Text(
            "autopilot — ai session",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TextDim,
            modifier = Modifier.weight(1f)
        )
        if (busy) {
            LinearProgressIndicator(
                Modifier.width(56.dp).height(3.dp),
                color = AccentGreen,
                trackColor = WinBorder
            )
            Spacer(Modifier.width(8.dp))
            Text("工作中", color = AccentGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(
                "停止",
                color = Color(0xFFFF6B6B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable(onClick = onStop).padding(4.dp)
            )
        } else {
            Text("● 待命", color = TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun WelcomeBlock() {
    Column {
        Text(
            "Autopilot Shell v0.1.0",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = AccentGreen,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "AI 已接管此终端。下方输入指令，或点选技能快捷执行。",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TextDim
        )
        Spacer(Modifier.height(8.dp))
        listOf(
            "❯ 说一句话，AI 直接回答",
            "❯ 说需求，AI 自动执行命令完成",
            "❯ 全程可见过程，随时停止"
        ).forEach {
            Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF6B7280))
        }
    }
}

@Composable
private fun TerminalMessage(entry: ChatEntry, animate: Boolean) {
    when (entry.role) {
        ChatRole.USER -> Row {
            Text("❯ ", color = AccentGreen, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(
                entry.text.take(1500),
                color = TextMain, fontSize = 13.sp
            )
        }
        ChatRole.AI -> Row {
            if (animate) TypewriterText(entry.text.take(2000))
            else Text(entry.text.take(2000), color = TextMain, fontSize = 13.sp, lineHeight = 18.sp)
        }
        ChatRole.CMD -> SurfaceCard {
            Text(
                "\$ ${entry.text.substringBefore("\n#")}",
                color = Cyan, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            entry.text.substringAfter("\n#", "").takeIf { it.isNotBlank() }?.let {
                Text("# $it", color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        ChatRole.OUTPUT -> SurfaceCard {
            Text(
                entry.text.take(1500),
                color = Color(0xFFB5BDCA),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        ChatRole.SYSTEM -> Row {
            Text("# ", color = AccentAmber, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(
                entry.text.take(500),
                color = AccentAmber.copy(alpha = 0.85f), fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun SurfaceCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(WinSurface, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
    ) { content() }
}

@Composable
private fun TypewriterText(full: String) {
    var shown by remember(full) { mutableStateOf(if (full.length > 600) full.length else 0) }
    LaunchedEffect(full) {
        if (full.length <= 600) {
            while (shown < full.length) {
                shown = (shown + 2).coerceAtMost(full.length)
                delay(16)
            }
        }
    }
    Text(
        full.take(shown) + if (shown < full.length) "▌" else "",
        color = TextMain, fontSize = 13.sp, lineHeight = 18.sp
    )
}

@Composable
private fun SkillChips(enabled: Boolean, onRun: (String) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        items(skills) { skill ->
            Text(
                skill.label,
                color = AccentGreen,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(WinSurface)
                    .clickable(enabled = enabled) { onRun(skill.prompt) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun GoalInput(vm: AutopilotViewModel, busy: Boolean) {
    var goal by remember { mutableStateOf("") }

    fun sendChat() {
        if (goal.isBlank() || busy) return
        vm.engine.chat(goal.trim())
        goal = ""
    }

    Row(
        Modifier.fillMaxWidth().background(WinSurface).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("❯", color = AccentGreen, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it },
            placeholder = { Text("输入指令...", fontSize = 12.sp, color = Color(0xFF565F73)) },
            modifier = Modifier.weight(1f),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp,
                color = TextMain,
                fontFamily = FontFamily.Monospace
            ),
            minLines = 1,
            maxLines = 3,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = WinBorder,
                unfocusedBorderColor = WinBorder,
                focusedContainerColor = WinBg,
                unfocusedContainerColor = WinBg,
                cursorColor = AccentGreen
            )
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { sendChat() },
            enabled = goal.isNotBlank() && !busy,
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Color(0xFF05210F))
        ) { Text("发送", fontSize = 12.sp) }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = {
                if (goal.isNotBlank() && !busy) {
                    vm.submitTask(goal.trim(), emptyList())
                    goal = ""
                }
            },
            enabled = goal.isNotBlank() && !busy
        ) { Text("任务", fontSize = 12.sp, color = AccentPurple) }
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
