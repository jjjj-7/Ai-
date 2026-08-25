package dev.autopilot.terminal.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autopilot.terminal.data.ModelConfig
import dev.autopilot.terminal.ui.AutopilotViewModel
import dev.autopilot.terminal.ui.collectAsStateSafe

@Composable
fun ModelSettingsScreen(vm: AutopilotViewModel) {
    val current by vm.config.collectAsStateSafe()
    var baseUrl by remember(current) { mutableStateOf(current.baseUrl) }
    var apiKey by remember(current) { mutableStateOf(current.apiKey) }
    var model by remember(current) { mutableStateOf(current.model) }
    var temperature by remember(current) { mutableStateOf(current.temperature.toString()) }
    var maxIter by remember(current) { mutableStateOf(current.maxIterations.toString()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("模型配置", fontSize = 18.sp, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text("兼容 OpenAI Chat Completions 协议。密钥仅保存在本机加密存储中。", fontSize = 12.sp, color = Color(0xFF9CA3AF))
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("API 地址") },
            placeholder = { Text("https://api.openai.com/v1") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API 密钥") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Row2Fields(
            leftLabel = "温度 (0-2)",
            leftValue = temperature,
            onLeft = { temperature = it },
            rightLabel = "自动迭代上限",
            rightValue = maxIter,
            onRight = { maxIter = it }
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            vm.saveConfig(
                ModelConfig(
                    baseUrl = baseUrl.trim(),
                    apiKey = apiKey.trim(),
                    model = model.trim(),
                    temperature = temperature.toDoubleOrNull() ?: 0.2,
                    maxIterations = maxIter.toIntOrNull() ?: 50
                )
            )
        }) {
            Text("保存配置")
        }

        Spacer(Modifier.height(24.dp))
        CrashLogSection()
    }
}

@Composable
private fun CrashLogSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var summary by remember { mutableStateOf("读取中...") }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        summary = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { dev.autopilot.terminal.CrashReporter.readableSummary(context) }.getOrDefault("暂无崩溃记录")
        }
    }
    Text("诊断信息", fontSize = 16.sp, color = Color.White)
    Spacer(Modifier.height(4.dp))
    Text("若应用闪退，请把以下内容复制发给开发者。长按文本可全选复制。", fontSize = 11.sp, color = Color(0xFF9CA3AF))
    Spacer(Modifier.height(6.dp))
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            summary,
            fontSize = 10.sp,
            color = Color(0xFFF87171),
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        )
    }
}

@Composable
private fun Row2Fields(
    leftLabel: String, leftValue: String, onLeft: (String) -> Unit,
    rightLabel: String, rightValue: String, onRight: (String) -> Unit
) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = leftValue,
            onValueChange = onLeft,
            label = { Text(leftLabel) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        OutlinedTextField(
            value = rightValue,
            onValueChange = onRight,
            label = { Text(rightLabel) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
    }
}
