package dev.autopilot.terminal.ui.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autopilot.terminal.ui.AccentPurple
import dev.autopilot.terminal.ui.AutopilotViewModel
import java.io.File

@Composable
fun FileTreeScreen(vm: AutopilotViewModel) {
    val root = vm.getApplication<dev.autopilot.terminal.AutopilotApp>().workspaceRoot
    var currentDir by remember { mutableStateOf(root) }
    var viewingFile by remember { mutableStateOf<File?>(null) }
    var transferMsg by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            val r = dev.autopilot.terminal.data.WorkspaceTransfer.exportToUri(context, uri, root)
            transferMsg = r.fold({ "已导出 $it 个文件" }, { "导出失败: ${it.message}" })
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val r = dev.autopilot.terminal.data.WorkspaceTransfer.importFromUri(context, uri, root)
            transferMsg = r.fold({ "已导入 $it 个文件到工作区" }, { "导入失败: ${it.message}" })
            refreshKey++
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("工作区", fontSize = 15.sp, color = Color.White)
                Text(
                    root.absolutePath,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFF6B7280)
                )
            }
            OutlinedButton(onClick = {
                runCatching { exportLauncher.launch("autopilot-workspace.zip") }
                    .onFailure { transferMsg = "无法启动导出: ${it.message}" }
            }) { Text("导出 zip") }
            Spacer(Modifier.width(6.dp))
            Button(onClick = {
                runCatching { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }
                    .onFailure { transferMsg = "无法选择文件: ${it.message}" }
            }) { Text("导入 zip") }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                currentDir.relativeToOrNull(root)?.path?.ifEmpty { "/" } ?: "/",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = AccentPurple,
                modifier = Modifier.weight(1f)
            )
            if (currentDir != root) {
                OutlinedButton(onClick = { currentDir = currentDir.parentFile ?: root }) { Text("上级") }
            }
        }

        transferMsg?.let {
            Text(it, fontSize = 12.sp, color = AccentPurple, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }

        key(refreshKey) {
            LazyColumn(Modifier.weight(1f)) {
                items(
                    currentDir.listFiles()
                        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        ?: emptyList()
                ) { entry ->
                    ListItem(
                        headlineContent = {
                            Text(
                                entry.name,
                                fontSize = 14.sp,
                                color = if (entry.isDirectory) Color.White else Color(0xFFD1D5DB)
                            )
                        },
                        leadingContent = {
                            if (entry.isDirectory) Icon(Icons.Filled.Folder, null, tint = AccentPurple)
                            else Text("·", fontSize = 18.sp, color = Color(0xFF6B7280))
                        },
                        modifier = Modifier.padding(horizontal = 4.dp)
                            .then(
                                if (entry.isDirectory)
                                    Modifier.clickable { currentDir = entry }
                                else
                                    Modifier.clickable { viewingFile = entry }
                            )
                    )
                }
            }
        }
    }

    viewingFile?.let { file ->
        AlertDialog(
            onDismissRequest = { viewingFile = null },
            title = { Text(file.name, fontSize = 15.sp) },
            text = {
                Text(
                    runCatching { file.readText().take(8000) }.getOrElse { "(无法读取: ${it.message})" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            },
            confirmButton = { TextButton(onClick = { viewingFile = null }) { Text("关闭") } }
        )
    }
}
