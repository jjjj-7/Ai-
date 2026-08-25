package dev.autopilot.terminal.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun <T> StateFlow<T>.collectAsStateSafe(): State<T> = collectAsState()

@Composable
fun RiskOnboarding(onAccept: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = { Text("自动化风险提示") },
        text = {
            Text(
                "本应用的 AI 代理将在终端中自动执行命令，包括创建、修改与删除文件。" +
                    "虽然内置高危命令拦截，仍可能产生预期外的系统变更。\n\n" +
                    "请确认你了解并接受以下事项:\n" +
                    "1. AI 执行的每条命令都会记入审计日志\n" +
                    "2. 高危命令会暂停等待人工确认\n" +
                    "3. 请勿在任务目标中包含破坏性指令"
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onAccept) { Text("我已了解并接受") }
        }
    )
}
