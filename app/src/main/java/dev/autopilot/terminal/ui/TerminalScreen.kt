package dev.autopilot.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autopilot.terminal.agent.AgentUiState
import dev.autopilot.terminal.bootstrap.BootstrapInstaller
import dev.autopilot.terminal.terminal.Span
import dev.autopilot.terminal.terminal.TerminalSessionState

@Composable
fun TerminalScreen(vm: AutopilotViewModel) {
    val bootstrapState by vm.installer.state.collectAsStateSafe()
    val agentState by vm.engine.uiState.collectAsStateSafe()
    val sessions by vm.registry.sessions.collectAsStateSafe()

    Column(
        Modifier
            .fillMaxSize()
            .background(TerminalBlack)
    ) {
        when (val bs = bootstrapState) {
            is BootstrapInstaller.InstallState.Downloading ->
                BootstrapBar("正在下载用户态环境 ${bs.pkg} (${bs.index}/${bs.total})")
            is BootstrapInstaller.InstallState.Extracting ->
                BootstrapBar("正在解压用户态环境...")
            is BootstrapInstaller.InstallState.Failed -> BootstrapFailed(bs.reason, vm)
            else -> Unit
        }

        val active = sessions.firstOrNull()
        if (active == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("终端会话未创建", color = Color(0xFF9CA3AF))
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.registry.create(AutopilotViewModel.AGENT_SESSION) }) {
                        Text("启动终端")
                    }
                }
            }
        } else {
            TerminalView(active, Modifier.weight(1f))
        }

        TaskPanel(agentState, vm)
        val inputScope = rememberCoroutineScope()
        InputLine(
            enabled = bootstrapState is BootstrapInstaller.InstallState.Ready,
            onSend = { line ->
                active?.session?.let { session ->
                    inputScope.launch { session.writeLine(line) }
                }
            }
        )
    }
}

@Composable
private fun InputLine(enabled: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().background(TerminalSurface).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$ ", color = AccentGreen, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        Spacer(Modifier.width(4.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            ),
            modifier = Modifier.weight(1f).height(36.dp),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Send
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                if (text.isNotBlank()) {
                    onSend(text)
                    text = ""
                }
            })
        )
    }
}

@Composable
private fun BootstrapBar(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
        Text(text, color = Color(0xFF9CA3AF), fontSize = 13.sp)
    }
}

@Composable
private fun BootstrapFailed(reason: String, vm: AutopilotViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("环境安装失败: $reason", color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = vm::retryBootstrap) { Text("重试") }
    }
}

@Composable
fun TerminalView(state: TerminalSessionState, modifier: Modifier = Modifier) {
    val lines = remember(state.session.name) { mutableStateOf(state.buffer.snapshot()) }
    LaunchedEffect(state) {
        state.session.output.collect {
            lines.value = state.buffer.snapshot()
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.value.size) {
        if (lines.value.isNotEmpty()) listState.animateScrollToItem(lines.value.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().background(Color(0xFF0C0C14)).padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        items(lines.value) { line ->
            Text(
                text = renderStyled(line.text, line.spans),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

internal fun renderStyled(text: String, spans: List<Span>): AnnotatedString = buildAnnotatedString {
    if (spans.isEmpty() || text.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var cursor = 0
    spans.sortedBy { it.start }.forEach { s ->
        val start = s.start.coerceIn(0, text.length)
        val end = s.end.coerceIn(start, text.length)
        if (start > cursor) {
            withStyle(SpanStyle(color = ansiColor(null))) { append(text.substring(cursor, start)) }
            cursor = start
        }
        if (end > cursor) {
            val style = SpanStyle(
                color = ansiColor(s.fg),
                background = ansiColor(s.bg),
                fontWeight = if (s.bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
                textDecoration = if (s.underline) androidx.compose.ui.text.style.TextDecoration.Underline else null
            )
            withStyle(style) { append(text.substring(cursor, end)) }
            cursor = end
        }
    }
    if (cursor < text.length) {
        withStyle(SpanStyle(color = ansiColor(null))) { append(text.substring(cursor)) }
    }
}
