package dev.autopilot.terminal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.itemsIndexed
import dev.autopilot.terminal.agent.AgentEngine
import dev.autopilot.terminal.agent.AgentUiState
import dev.autopilot.terminal.agent.ChatEntry
import dev.autopilot.terminal.agent.ChatRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatPanel(vm: AutopilotViewModel, modifier: Modifier = Modifier) {
    val state by vm.engine.uiState.collectAsStateSafe()
    val chat by vm.engine.chat.collectAsStateSafe()
    val busy by vm.engine.busy.collectAsStateSafe()
    val todos by vm.engine.todos.collectAsStateSafe()
    val streamingText by vm.engine.streamingText.collectAsStateSafe()
    val contextUsage by vm.engine.contextUsage.collectAsStateSafe()
    val sessionStats by vm.engine.sessionStats.collectAsStateSafe()
    val listState = rememberLazyListState()
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= chat.size - 2 || chat.size <= 3
        }
    }
    var wasAtBottom by remember { mutableStateOf(true) }

    LaunchedEffect(isAtBottom) {
        wasAtBottom = isAtBottom
    }

    LaunchedEffect(chat.size, streamingText.length) {
        if (chat.isNotEmpty() && wasAtBottom) {
            listState.animateScrollToItem(chat.size - 1)
        }
    }

    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var prevBusy by remember { mutableStateOf(false) }

    LaunchedEffect(busy) {
        if (prevBusy && !busy && chat.isNotEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        prevBusy = busy
    }

    Box(modifier.background(WinBg)) {
        NebulaBackdrop()
        Column(Modifier.fillMaxSize()) {
            WindowTitleBar(busy, state, onStop = { vm.engine.stop() })

            ContextUsageBar(contextUsage)

            if (sessionStats.iterations > 0 || sessionStats.toolsCalled > 0) {
                SessionStatsBar(sessionStats)
            }

            FlowingGradientLine(Modifier.fillMaxWidth().height(2.dp))

            if (todos.isNotEmpty()) {
                TodoPanel(todos)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (chat.isEmpty() && streamingText.isEmpty()) item { WelcomeBlock() }
                itemsIndexed(chat) { idx, entry ->
                    val isLast = idx == chat.lastIndex
                    val clipboard = LocalClipboardManager.current
                    SlideFadeIn {
                        Box(
                            Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    clipboard.setText(AnnotatedString(entry.text))
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            )
                        ) {
                            TerminalMessage(entry, animate = isLast && entry.role == ChatRole.AI && !busy)
                        }
                    }
                }
                if (streamingText.isNotBlank() && busy) {
                    item {
                        StreamingMessage(streamingText)
                    }
                }
            }

            when (val s = state) {
                is AgentUiState.AwaitConfirm -> ConfirmDialog(s, vm)
                else -> Unit
            }

            GoalInput(vm, busy)
        }
        SweepBandOverlay()

        if (!isAtBottom && chat.size > 5) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 70.dp)
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(WinSurface.copy(alpha = 0.9f))
                        .border(1.dp, Cyan.copy(alpha = 0.4f), CircleShape)
                        .clickable {
                            wasAtBottom = true
                            scope.launch { listState.animateScrollToItem(chat.size - 1) }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("v", color = Cyan, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun WindowTitleBar(busy: Boolean, state: AgentUiState, onStop: () -> Unit) {
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
            val statusText = when (state) {
                is AgentUiState.Streaming -> "思考中"
                is AgentUiState.Executing -> {
                    val toolName = state.toolName.ifBlank { "执行" }
                    "$toolName #${state.stepIndex + 1}"
                }
                is AgentUiState.Planning -> "规划中"
                is AgentUiState.AwaitConfirm -> "等待确认"
                else -> "工作中"
            }
            LinearProgressIndicator(
                Modifier.width(56.dp).height(3.dp),
                color = AccentGreen,
                trackColor = WinBorder
            )
            Spacer(Modifier.width(8.dp))
            Text(statusText, color = AccentGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(
                "停止",
                color = Color(0xFFFF6B6B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Pulsing().clickable(onClick = onStop).padding(4.dp)
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(7.dp).clip(CircleShape).background(AccentGreen),
                    contentAlignment = Alignment.Center
                ) {}
                Spacer(Modifier.width(5.dp))
                Text("待命", color = TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ContextUsageBar(usage: Float) {
    val pct = (usage * 100).toInt().coerceIn(0, 100)
    if (pct == 0 && usage == 0f) return
    val color = when {
        pct >= 85 -> Color(0xFFFF6B6B)
        pct >= 60 -> Color(0xFFE8C76B)
        else -> AccentGreen
    }
    Row(
        Modifier.fillMaxWidth().background(WinSurface.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("ctx", color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(WinBorder)
        ) {
            Box(
                Modifier.fillMaxWidth(usage.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("${pct}%", color = color, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SessionStatsBar(stats: AgentEngine.SessionStats) {
    Row(
        Modifier.fillMaxWidth().background(WinSurface.copy(alpha = 0.3f)).padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val items = listOf(
            Triple("iter", stats.iterations.toString(), AccentGreen),
            Triple("tok", if (stats.totalTokens >= 1000) "${stats.totalTokens / 1000}k" else stats.totalTokens.toString(), Cyan),
            Triple("tool", stats.toolsCalled.toString(), AccentPurple),
            Triple("file", stats.filesModified.toString(), Color(0xFFE8C76B)),
            Triple("cmd", stats.commandsRun.toString(), Color(0xFFE876B0))
        )
        items.forEachIndexed { idx, (label, value, color) ->
            if (idx > 0) { Spacer(Modifier.width(10.dp)); Text("|", color = TextDim.copy(alpha = 0.3f), fontSize = 9.sp, fontFamily = FontFamily.Monospace); Spacer(Modifier.width(10.dp)) }
            Text(label, color = TextDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(3.dp))
            Text(value, color = color, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WelcomeBlock() {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Autopilot Shell",
                fontFamily = FontFamily.Monospace,
                fontSize = 17.sp,
                color = AccentGreen,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "v0.1.0",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = Cyan,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, Cyan.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "AI 已接管此终端。原生工具调用, 并行执行, 流式输出。",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TextDim
        )
        Spacer(Modifier.height(12.dp))
        listOf(
            Triple("●", AccentGreen, "27 个原生工具 — 文件/Git/Web/测试/子Agent/网络"),
            Triple("◆", AccentPurple, "并行执行 + 流式输出 — 看 AI 边想边做"),
            Triple("▲", Cyan, "diff 预览 + 自动 lint + 撤销 — 代码变更安全可控"),
            Triple("■", Color(0xFFE8C76B), "Web 搜索 + URL 抓取 — Claude Code 没有的"),
            Triple("★", Color(0xFF7EE3C8), "斜杠命令 + @文件引用 — 快捷交互"),
            Triple("♦", Color(0xFFE876B0), "智能滚动 + 触觉反馈 + 会话统计")
        ).forEach { (mark, markColor, line) ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(mark, color = markColor, fontSize = 9.sp)
                Spacer(Modifier.width(8.dp))
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF8B94A7))
            }
        }
    }
}

@Composable
private fun TerminalMessage(entry: ChatEntry, animate: Boolean) {
    when (entry.role) {
        ChatRole.USER -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                entry.text.take(1500),
                color = Color(0xFFD9FBE8),
                fontSize = 13.sp,
                modifier = Modifier
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF12291F), Color(0xFF101E33))),
                        RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp)
                    )
                    .border(1.dp, AccentGreen.copy(alpha = 0.20f), RoundedCornerShape(14.dp, 4.dp, 14.dp, 14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        ChatRole.AI -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(2.5.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.verticalGradient(listOf(AccentGreen, Cyan)))
            )
            Spacer(Modifier.width(10.dp))
            Column {
                if (animate) TypewriterText(entry.text.take(2000))
                else SimpleMarkdown(entry.text.take(2000))
            }
        }
        ChatRole.CMD -> SurfaceCard(accent = toolAccent(entry.toolName)) {
            val icon = toolIcon(entry.toolName)
            Text(
                "$icon ${entry.text.substringBefore("\n#")}",
                color = toolColor(entry.toolName), fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            entry.text.substringAfter("\n#", "").takeIf { it.isNotBlank() }?.let {
                Text("# $it", color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        ChatRole.OUTPUT -> SurfaceCard(accent = WinBorder) {
            val text = entry.text.take(1500)
            val hasDiff = entry.toolName == "edit_file" || entry.toolName == "write_file"
            if (hasDiff) {
                val lines = text.split("\n")
                Column {
                    lines.forEach { line ->
                        val color = when {
                            line.startsWith("+ ") || line.startsWith("++ ") || line.startsWith("+\t") -> Color(0xFF4EC9B0)
                            line.startsWith("- ") || line.startsWith("-- ") || line.startsWith("-\t") -> Color(0xFFF44747)
                            line.startsWith("--- diff") || line.startsWith("--- new file") -> Cyan
                            else -> Color(0xFFB5BDCA)
                        }
                        Text(
                            line,
                            color = color,
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            } else {
                Text(
                    text,
                    color = Color(0xFFB5BDCA),
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        ChatRole.SYSTEM -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(2.5.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(AccentAmber.copy(alpha = 0.7f))
            )
            Spacer(Modifier.width(10.dp))
            Text(
                entry.text.take(500),
                color = AccentAmber.copy(alpha = 0.90f), fontSize = 12.sp, lineHeight = 17.sp
            )
        }
        ChatRole.THINKING -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(2.5.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(AccentPurple.copy(alpha = 0.5f))
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "thinking",
                    color = AccentPurple.copy(alpha = 0.6f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                Text(
                    entry.text.take(2000),
                    color = Color(0xFF7A82A0),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        }
        ChatRole.TOOL_CALL -> Unit
    }
}

private fun toolIcon(toolName: String?): String = when (toolName) {
    "execute" -> "$"
    "batch" -> "II"
    "read_file" -> "cat"
    "write_file" -> ">"
    "edit_file" -> "~"
    "multi_edit" -> "~x"
    "undo_edit" -> "undo"
    "glob" -> "*"
    "grep" -> "?"
    "tree" -> "tree"
    "runbg" -> "bg"
    "joblog" -> "log"
    "wait" -> "..."
    "todo" -> "[ ]"
    "web_search" -> "web"
    "web_fetch" -> "get"
    "dns_lookup" -> "dns"
    "port_check" -> "port"
    "git_status" -> "git"
    "git_diff" -> "diff"
    "git_commit" -> "commit"
    "run_tests" -> "test"
    "dispatch_subagent" -> "sub"
    "listen" -> "ask"
    else -> ">"
}

private fun toolColor(toolName: String?): Color = when (toolName) {
    "execute", "batch" -> Cyan
    "read_file" -> Color(0xFF7EC8E3)
    "write_file", "edit_file", "multi_edit" -> AccentGreen
    "undo_edit" -> Color(0xFFE8A070)
    "glob", "grep", "tree" -> AccentPurple
    "runbg", "joblog" -> Color(0xFFE8C76B)
    "web_search", "web_fetch" -> Color(0xFF7EE3C8)
    "dns_lookup", "port_check" -> Color(0xFFC8B0E3)
    "git_status", "git_diff", "git_commit" -> Color(0xFFE876B0)
    "run_tests" -> Color(0xFFE8E37E)
    "dispatch_subagent" -> Color(0xFFB0C8E3)
    "listen" -> Color(0xFFE3B0C8)
    else -> Cyan
}

private fun toolAccent(toolName: String?): Color = when (toolName) {
    "execute", "batch" -> Cyan.copy(alpha = 0.35f)
    "read_file" -> Color(0xFF7EC8E3).copy(alpha = 0.30f)
    "write_file", "edit_file", "multi_edit" -> AccentGreen.copy(alpha = 0.35f)
    "undo_edit" -> Color(0xFFE8A070).copy(alpha = 0.30f)
    "glob", "grep", "tree" -> AccentPurple.copy(alpha = 0.35f)
    "runbg", "joblog" -> Color(0xFFE8C76B).copy(alpha = 0.30f)
    "web_search", "web_fetch" -> Color(0xFF7EE3C8).copy(alpha = 0.30f)
    "dns_lookup", "port_check" -> Color(0xFFC8B0E3).copy(alpha = 0.30f)
    "git_status", "git_diff", "git_commit" -> Color(0xFFE876B0).copy(alpha = 0.30f)
    "run_tests" -> Color(0xFFE8E37E).copy(alpha = 0.30f)
    "dispatch_subagent" -> Color(0xFFB0C8E3).copy(alpha = 0.30f)
    "listen" -> Color(0xFFE3B0C8).copy(alpha = 0.30f)
    else -> WinBorder
}

@Composable
private fun StreamingMessage(text: String) {
    val cursorAlpha by rememberInfiniteTransition().animateFloat(
        initialValue = 1f, targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse)
    )
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(2.5.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(listOf(AccentPurple, Cyan)))
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                "thinking",
                color = AccentPurple.copy(alpha = 0.6f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Text(
                buildAnnotatedString {
                    append(text.take(3000))
                    withStyle(SpanStyle(color = Cyan.copy(alpha = cursorAlpha))) { append("▌") }
                },
                color = Color(0xFF8B94B0),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        }
    }
}

@Composable
private fun SurfaceCard(accent: Color = WinBorder, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(WinSurface)
            .border(1.dp, accent.copy(alpha = 0.55f), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) { content() }
}

@Composable
private fun TodoPanel(todos: List<dev.autopilot.terminal.agent.AgentEngine.TodoItem>) {
    val doneN = todos.count { it.done }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(WinSurface.copy(alpha = 0.92f))
            .border(1.dp, AccentPurple.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("进度", color = AccentPurple, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("$doneN/${todos.size}", color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(WinBorder)) {
                Box(
                    Modifier
                        .fillMaxWidth(doneN.toFloat() / todos.size.coerceAtLeast(1))
                        .height(3.dp)
                        .background(Brush.horizontalGradient(listOf(AccentGreen, Cyan)))
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Column {
            todos.take(6).forEach { item ->
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.done) {
                        Text("✓", color = AccentGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Box(Modifier.size(8.dp).clip(CircleShape).border(1.5.dp, AccentPurple, CircleShape))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.text,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (item.done) Color(0xFF5F6B7F) else TextMain,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null
                    )
                }
            }
            if (todos.size > 6) {
                Text("... 共 ${todos.size} 项", fontSize = 10.sp, color = TextDim, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun NebulaBackdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(AccentPurple.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width * 0.12f, size.height * 0.06f),
                radius = size.width * 0.95f
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Cyan.copy(alpha = 0.08f), Color.Transparent),
                center = Offset(size.width * 0.92f, size.height * 0.85f),
                radius = size.width * 1.05f
            )
        )
    }
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
    val cursorAlpha by rememberInfiniteTransition().animateFloat(
        initialValue = 1f, targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse)
    )
    Text(
        buildAnnotatedString {
            append(full.take(shown))
            if (shown < full.length) {
                withStyle(SpanStyle(color = Cyan.copy(alpha = cursorAlpha))) { append("▌") }
            }
        },
        color = TextMain, fontSize = 13.sp, lineHeight = 19.sp
    )
}

@Composable
private fun SimpleMarkdown(text: String) {
    val lines = text.split("\n")
    var inCodeBlock = false
    val codeBlockLines = mutableListOf<String>()

    Column {
        for (line in lines) {
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    SurfaceCard(accent = Cyan.copy(alpha = 0.25f)) {
                        Text(
                            codeBlockLines.joinToString("\n").take(1500),
                            color = Color(0xFFC8D3E0),
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    codeBlockLines.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                continue
            }
            if (inCodeBlock) {
                codeBlockLines.add(line)
                continue
            }
            when {
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val content = line.trimStart().drop(2)
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text("•", color = Cyan, fontSize = 13.sp, modifier = Modifier.width(16.dp))
                        StyledText(content)
                    }
                }
                line.trimStart().startsWith("# ") -> {
                    StyledText(line.trimStart().drop(2), bold = true, size = 14)
                }
                line.trimStart().startsWith("## ") -> {
                    StyledText(line.trimStart().drop(3), bold = true, size = 13)
                }
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                else -> StyledText(line)
            }
        }
        if (codeBlockLines.isNotEmpty()) {
            SurfaceCard(accent = Cyan.copy(alpha = 0.25f)) {
                Text(
                    codeBlockLines.joinToString("\n").take(1500),
                    color = Color(0xFFC8D3E0),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun StyledText(text: String, bold: Boolean = false, size: Int = 13) {
    val annotated = buildAnnotatedString {
        var i = 0
        var currentBold = bold
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                withStyle(SpanStyle(
                    fontWeight = if (currentBold) FontWeight.Bold else FontWeight.Normal,
                    color = TextMain,
                    fontSize = size.sp,
                    fontFamily = if (currentBold) FontFamily.Default else FontFamily.Monospace
                )) { append(sb.toString()) }
                sb.clear()
            }
        }
        while (i < text.length) {
            if (i + 2 <= text.length && text[i] == '*' && text[i + 1] == '*') {
                flush()
                currentBold = !currentBold
                i += 2
            } else if (text[i] == '`') {
                flush()
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(
                        color = Cyan,
                        fontFamily = FontFamily.Monospace,
                        fontSize = size.sp
                    )) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    sb.append(text[i])
                    i++
                }
            } else {
                sb.append(text[i])
                i++
            }
        }
        flush()
    }
    Text(annotated, lineHeight = (size + 6).sp)
}

private data class SlashCommand(val cmd: String, val desc: String, val icon: String)

private val SLASH_COMMANDS = listOf(
    SlashCommand("/help", "显示所有可用命令", "?"),
    SlashCommand("/tools", "列出 27 个内置工具", "#"),
    SlashCommand("/clear", "清空聊天历史", "x"),
    SlashCommand("/status", "显示会话状态 (迭代/上下文)", "i"),
    SlashCommand("/undo", "撤销最后一次文件编辑", "undo"),
    SlashCommand("/export", "导出聊天记录到文件", "exp"),
    SlashCommand("/stop", "停止当前任务", "[]"),
    SlashCommand("/model", "显示当前模型配置", "M")
)

private fun handleSlashCommand(input: String, vm: AutopilotViewModel): Boolean {
    val parts = input.split(" ", limit = 2)
    val cmd = parts[0].lowercase()
    when (cmd) {
        "/help" -> {
            vm.engine.injectSystem(buildString {
                appendLine("可用斜杠命令:")
                SLASH_COMMANDS.forEach { c ->
                    appendLine("  ${c.icon} ${c.cmd.padEnd(10)} ${c.desc}")
                }
            })
        }
        "/tools" -> {
            vm.engine.injectSystem(buildString {
                appendLine("27 个内置工具:")
                val groups = listOf(
                    "文件操作" to listOf("read_file", "write_file", "edit_file", "multi_edit", "undo_edit", "glob", "grep", "tree"),
                    "执行" to listOf("execute", "batch", "runbg", "joblog", "wait"),
                    "Web/网络" to listOf("web_search", "web_fetch", "dns_lookup", "port_check"),
                    "Git" to listOf("git_status", "git_diff", "git_commit"),
                    "代码智能" to listOf("run_tests", "auto_lint"),
                    "Agent" to listOf("dispatch_subagent", "listen", "todo", "finish", "abort")
                )
                groups.forEach { (name, tools) ->
                    appendLine("  $name: ${tools.joinToString(", ")}")
                }
            })
        }
        "/clear" -> vm.engine.clearChat()
        "/status" -> {
            vm.engine.injectSystem(buildString {
                appendLine("会话状态:")
                appendLine("  上下文使用: ${(vm.engine.contextUsage.value * 100).toInt()}%")
                appendLine("  消息数: ${vm.engine.chat.value.size}")
                appendLine("  流式输出: ${if (vm.engine.streamingText.value.isNotEmpty()) "有" else "无"}")
            })
        }
        "/undo" -> vm.engine.undoLastEdit()
        "/export" -> {
            val path = vm.engine.exportChat()
            vm.engine.injectSystem("聊天记录已导出到: $path")
        }
        "/stop" -> vm.engine.stop("用户通过 /stop 命令停止")
        "/model" -> {
            val cfg = vm.config.value
            vm.engine.injectSystem("当前模型: ${cfg.model}\nBase URL: ${cfg.baseUrl}\n最大迭代: ${cfg.maxIterations}")
        }
        else -> return false
    }
    return true
}

@Composable
private fun GoalInput(vm: AutopilotViewModel, busy: Boolean) {
    var goal by remember { mutableStateOf("") }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        if (focused) Cyan.copy(alpha = 0.65f) else WinBorder,
        animationSpec = tween(220)
    )
    val history = remember { mutableListOf<String>() }
    var historyIdx by remember { mutableStateOf(-1) }

    fun sendChat() {
        if (goal.isBlank() || busy) return
        val msg = goal.trim()
        if (msg.startsWith("/") && handleSlashCommand(msg, vm)) {
            goal = ""
            return
        }
        if (msg.isNotBlank() && (history.isEmpty() || history.last() != msg)) {
            history.add(msg)
        }
        historyIdx = -1
        vm.engine.chat(msg)
        goal = ""
    }

    fun navigateHistory(up: Boolean) {
        if (history.isEmpty()) return
        if (up) {
            if (historyIdx < history.size - 1) {
                historyIdx++
                goal = history[history.size - 1 - historyIdx]
            }
        } else {
            if (historyIdx > 0) {
                historyIdx--
                goal = history[history.size - 1 - historyIdx]
            } else {
                historyIdx = -1
                goal = ""
            }
        }
    }

    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    Row(
        Modifier
            .fillMaxWidth()
            .background(WinSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val pillShape = RoundedCornerShape(24.dp)
        Row(
            Modifier
                .weight(1f)
                .clip(pillShape)
                .background(WinBg)
                .border(1.dp, borderColor, pillShape)
                .padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("❯", color = AccentGreen, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = goal,
                onValueChange = {
                    goal = it
                    historyIdx = -1
                },
                modifier = Modifier.weight(1f).padding(vertical = 9.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.sp,
                    color = TextMain,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp
                ),
                cursorBrush = SolidColor(AccentGreen),
                interactionSource = interaction,
                maxLines = 3,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = { sendChat(); keyboardController?.hide() }
                ),
                decorationBox = { inner ->
                    Box {
                        if (goal.isEmpty()) {
                            Text("输入指令... (/ 命令, @ 文件, ↑↓ 历史)", fontSize = 12.sp, color = Color(0xFF565F73))
                        }
                        inner()
                        if (goal.startsWith("/") && goal.length <= 15 && !goal.contains(" ")) {
                            val matches = SLASH_COMMANDS.filter { it.cmd.startsWith(goal.lowercase()) }
                            if (matches.isNotEmpty()) {
                                DropdownMenu(
                                    expanded = true,
                                    onDismissRequest = {},
                                    modifier = Modifier.background(WinSurface).border(1.dp, Cyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                ) {
                                    matches.take(5).forEach { sc ->
                                        Row(
                                            Modifier.fillMaxWidth().clickable {
                                                goal = sc.cmd + " "
                                            }.padding(horizontal = 12.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(sc.icon, color = AccentPurple, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(28.dp))
                                            Text(sc.cmd, color = AccentGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp))
                                            Text(sc.desc, color = TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                            }
                        }
                        val atIdx = goal.lastIndexOf('@')
                        if (atIdx >= 0 && atIdx > 0 && goal[atIdx - 1] == ' ') {
                            val query = goal.substring(atIdx + 1)
                            if (!query.contains(' ') && query.length <= 30) {
                                val files = vm.suggestFiles(query)
                                if (files.isNotEmpty()) {
                                    DropdownMenu(
                                        expanded = true,
                                        onDismissRequest = {},
                                        modifier = Modifier.background(WinSurface).border(1.dp, AccentPurple.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    ) {
                                        Text("@文件", color = AccentPurple, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                        files.take(8).forEach { path ->
                                            Row(
                                                Modifier.fillMaxWidth().clickable {
                                                    goal = goal.substring(0, atIdx + 1) + path + " "
                                                }.padding(horizontal = 12.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                    Text(path, color = Color(0xFF7EE3C8), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            )
        }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .size(28.dp)
                .graphicsLayer { alpha = if (history.isNotEmpty()) 0.6f else 0.2f }
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = history.isNotEmpty()) { navigateHistory(true) },
            contentAlignment = Alignment.Center
        ) {
            Text("↑", color = TextDim, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        }
        Box(
            Modifier
                .size(28.dp)
                .graphicsLayer { alpha = if (historyIdx > 0) 0.6f else 0.2f }
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = historyIdx > 0) { navigateHistory(false) },
            contentAlignment = Alignment.Center
        ) {
            Text("↓", color = TextDim, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.width(4.dp))

        val canSend = goal.isNotBlank() && !busy
        Box(
            Modifier
                .size(40.dp)
                .graphicsLayer { alpha = if (canSend) 1f else 0.35f }
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(AccentGreen, Cyan)))
                .clickable(enabled = canSend) { sendChat() },
            contentAlignment = Alignment.Center
        ) {
            Text("↑", color = Color(0xFF04140B), fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .graphicsLayer { alpha = if (canSend) 1f else 0.35f }
                .clip(RoundedCornerShape(20.dp))
                .background(AccentPurple.copy(alpha = 0.10f))
                .border(1.dp, AccentPurple.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .clickable(enabled = canSend) {
                    vm.submitTask(goal.trim(), emptyList())
                    goal = ""
                }
                .padding(horizontal = 13.dp, vertical = 9.dp)
        ) {
            Text("任务", fontSize = 12.sp, color = AccentPurple, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ConfirmDialog(state: AgentUiState.AwaitConfirm, vm: AutopilotViewModel) {
    AlertDialog(
        onDismissRequest = {},
        shape = RoundedCornerShape(16.dp),
        containerColor = WinSurface,
        titleContentColor = TextMain,
        textContentColor = TextDim,
        title = { Text("高危命令确认", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(AccentAmber))
                    Spacer(Modifier.width(6.dp))
                    Text("原因: ${state.reason}", color = AccentAmber, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "\$ ${state.command}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Cyan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(WinBg)
                        .border(1.dp, WinBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = vm.engine::confirm,
                colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = Color(0xFF231A02))
            ) { Text("放行") }
        },
        dismissButton = {
            OutlinedButton(onClick = vm.engine::reject) { Text("拒绝") }
        }
    )
}
