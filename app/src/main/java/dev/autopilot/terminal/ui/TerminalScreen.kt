package dev.autopilot.terminal.ui

import android.view.MotionEvent
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.autopilot.terminal.bootstrap.BootstrapInstaller

@Composable
fun TerminalScreen(vm: AutopilotViewModel) {
    val bootstrapState by vm.installer.state.collectAsStateSafe()
    val sessions by vm.registry.sessions.collectAsStateSafe()
    val openAt by vm.openTerminalAt.collectAsStateSafe()

    LaunchedEffect(bootstrapState) {
        if (bootstrapState is BootstrapInstaller.InstallState.Idle) {
            vm.retryBootstrap()
        }
    }

    LaunchedEffect(openAt) {
        val dir = openAt ?: return@LaunchedEffect
        val ready = bootstrapState is BootstrapInstaller.InstallState.Ready
        if (ready && dir.isDirectory) {
            val state = sessions.firstOrNull { it.interactive && it.session.isRunning }
                ?: vm.registry.createInteractive(AutopilotViewModel.AGENT_SESSION)
            state?.session?.let {
                val cmd = "cd '${dir.absolutePath}'\n".toByteArray()
                it.write(cmd, 0, cmd.size)
            }
        }
        vm.openTerminalAt.value = null
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TerminalBlack)
    ) {
        when (val bs = bootstrapState) {
            is BootstrapInstaller.InstallState.Installing ->
                BootstrapBar("正在安装 Termux 用户态环境（bash/apt/clang/python/node）...")
            is BootstrapInstaller.InstallState.Failed -> BootstrapFailed(bs.reason, vm)
            else -> Unit
        }

        val active = sessions.firstOrNull { it.interactive && it.session.isRunning }
        if (active != null) {
            RealTerminalView(
                state = active,
                onSessionOutput = {},
                modifier = Modifier.fillMaxWidth().weight(0.34f)
            )
        } else {
            vm.registry.pruneDead()
            Box(Modifier.weight(0.34f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 20.dp)) {
                    val bashExists = remember {
                        java.io.File(vm.installer.prefix, "bin/bash").let { it.exists() && it.canExecute() }
                    }
                    val stateLabel = when (val bs = bootstrapState) {
                        is BootstrapInstaller.InstallState.Ready -> "环境就绪，终端未运行"
                        is BootstrapInstaller.InstallState.Installing -> "正在安装环境..."
                        is BootstrapInstaller.InstallState.Failed -> "安装失败: ${bs.reason}"
                        else ->
                            if (bashExists) "检测到已装环境，点下方按钮同步"
                            else "环境未安装 (状态: Idle) — 点「开始安装」触发"
                    }
                    Text(
                        stateLabel,
                        color = if (bootstrapState is BootstrapInstaller.InstallState.Failed) AccentAmber else Color(0xFF9CA3AF),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        if (bootstrapState !is BootstrapInstaller.InstallState.Ready) {
                            OutlinedButton(
                                onClick = { vm.retryBootstrap() },
                                enabled = bootstrapState !is BootstrapInstaller.InstallState.Installing
                            ) { Text(if (bashExists) "刷新状态" else "开始安装") }
                            Spacer(Modifier.width(8.dp))
                        }
                        Button(
                            onClick = { vm.registry.createInteractive(AutopilotViewModel.AGENT_SESSION) },
                            enabled = bootstrapState is BootstrapInstaller.InstallState.Ready
                        ) {
                            Text("启动终端")
                        }
                    }
                }
            }
        }

        ChatPanel(vm, Modifier.weight(0.66f).fillMaxWidth())
    }
}

@Composable
private fun RealTerminalView(
    state: dev.autopilot.terminal.terminal.TermuxSessionState,
    onSessionOutput: () -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                setTextSize((12 * resources.displayMetrics.scaledDensity).toInt())
                setTerminalViewClient(object : com.termux.view.TerminalViewClient {
                    override fun onScale(scale: Float): Float = scale
                    override fun onSingleTapUp(e: MotionEvent?) {}
                    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                    override fun shouldEnforceCharBasedInput(): Boolean = true
                    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                    override fun isTerminalViewSelected(): Boolean = true
                    override fun copyModeChanged(copyMode: Boolean) {}
                    override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent?, session: TerminalSession?): Boolean = false
                    override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent?): Boolean = false
                    override fun onLongPress(event: MotionEvent?): Boolean = false
                    override fun readControlKey(): Boolean = false
                    override fun readAltKey(): Boolean = false
                    override fun readShiftKey(): Boolean = false
                    override fun readFnKey(): Boolean = false
                    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
                    override fun onEmulatorSet() {}
                    override fun logError(tag: String?, message: String?) {}
                    override fun logWarn(tag: String?, message: String?) {}
                    override fun logInfo(tag: String?, message: String?) {}
                    override fun logDebug(tag: String?, message: String?) {}
                    override fun logVerbose(tag: String?, message: String?) {}
                    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
                    override fun logStackTrace(tag: String?, e: Exception?) {}
                })
                attachSession(state.session)
                setOnKeyListener { _, _, event ->
                    false
                }
            }
        },
        update = { view ->
            view.updateSize()
            view.invalidate()
        },
        modifier = modifier
    )
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
