package dev.autopilot.terminal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.animateColorAsState
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
import dev.autopilot.terminal.agent.SkillDef
import dev.autopilot.terminal.agent.SkillsRegistry

@Composable
fun ChatPanel(vm: AutopilotViewModel, modifier: Modifier = Modifier) {
    val state by vm.engine.uiState.collectAsStateSafe()
    val chat by vm.engine.chat.collectAsStateSafe()
    val busy by vm.engine.busy.collectAsStateSafe()
    val listState = rememberLazyListState()

    LaunchedEffect(chat.size) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1)
    }

    var userSkills by remember { mutableStateOf(emptyList<SkillDef>()) }
    LaunchedEffect(busy, chat.size) {
        if (!busy) {
            userSkills = withContext(Dispatchers.IO) {
                SkillsRegistry.loadUserSkills(vm.installer.homeDir)
            }
        }
    }
    val allSkills: List<SkillDef> = remember(userSkills) { SkillsRegistry.builtin + userSkills }

    Box(modifier.background(WinBg)) {
        NebulaBackdrop()
        Column(Modifier.fillMaxSize()) {
            WindowTitleBar(busy, onStop = { vm.engine.stop() })

            FlowingGradientLine(Modifier.fillMaxWidth().height(2.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (chat.isEmpty()) item { WelcomeBlock() }
                itemsIndexed(chat) { idx, entry ->
                    val isLast = idx == chat.lastIndex
                    SlideFadeIn {
                        TerminalMessage(entry, animate = isLast && entry.role == ChatRole.AI && !busy)
                    }
                }
            }

            when (val s = state) {
                is AgentUiState.AwaitConfirm -> ConfirmDialog(s, vm)
                else -> Unit
            }

            SkillChips(allSkills, enabled = !busy) { vm.engine.chat(it) }

            GoalInput(vm, busy)
        }
        SweepBandOverlay()
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
            "AI 已接管此终端。下方输入指令，或点选技能快捷执行。",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TextDim
        )
        Spacer(Modifier.height(12.dp))
        listOf(
            Triple("●", AccentGreen, "说一句话，AI 直接回答"),
            Triple("◆", AccentPurple, "说需求，AI 自动执行命令完成"),
            Triple("▲", Cyan, "全程可见过程，随时停止")
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
                else Text(entry.text.take(2000), color = TextMain, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
        ChatRole.CMD -> SurfaceCard(accent = Cyan.copy(alpha = 0.35f)) {
            Text(
                "\$ ${entry.text.substringBefore("\n#")}",
                color = Cyan, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            entry.text.substringAfter("\n#", "").takeIf { it.isNotBlank() }?.let {
                Text("# $it", color = TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        ChatRole.OUTPUT -> SurfaceCard(accent = WinBorder) {
            Text(
                entry.text.take(1500),
                color = Color(0xFFB5BDCA),
                fontSize = 10.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace
            )
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
private fun SkillChips(skills: List<SkillDef>, enabled: Boolean, onRun: (String) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        itemsIndexed(skills) { idx, skill ->
            ChipReveal(idx) {
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(120))
                val chipShape = RoundedCornerShape(20.dp)
                Text(
                    "◈ ${skill.label}",
                    color = AccentGreen,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(chipShape)
                        .background(if (pressed) WinBorder.copy(alpha = 0.35f) else WinSurface)
                        .border(1.dp, AccentGreen.copy(alpha = if (enabled) 0.22f else 0.10f), chipShape)
                        .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onRun(skill.prompt) }
                        .padding(horizontal = 13.dp, vertical = 7.dp)
                )
            }
        }
    }
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

    fun sendChat() {
        if (goal.isBlank() || busy) return
        vm.engine.chat(goal.trim())
        goal = ""
    }

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
                onValueChange = { goal = it },
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
                decorationBox = { inner ->
                    Box {
                        if (goal.isEmpty()) {
                            Text("输入指令...", fontSize = 12.sp, color = Color(0xFF565F73))
                        }
                        inner()
                    }
                }
            )
        }
        Spacer(Modifier.width(8.dp))

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
