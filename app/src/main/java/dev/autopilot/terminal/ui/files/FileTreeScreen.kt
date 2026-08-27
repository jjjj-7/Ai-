package dev.autopilot.terminal.ui.files

import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.autopilot.terminal.AutopilotApp
import dev.autopilot.terminal.ui.AccentAmber
import dev.autopilot.terminal.ui.AccentGreen
import dev.autopilot.terminal.ui.AccentPurple
import dev.autopilot.terminal.ui.TerminalBlack
import dev.autopilot.terminal.ui.TerminalSurface
import dev.autopilot.terminal.ui.TextDim
import dev.autopilot.terminal.ui.TextMain
import dev.autopilot.terminal.ui.AutopilotViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class DirState(val entries: List<File> = emptyList(), val error: String? = null)

private val CardColor = Color(0xFFFFFFFF)
private val GrayText = Color(0xFF6B7280)

@Composable
fun FileTreeScreen(vm: AutopilotViewModel) {
    val context = LocalContext.current
    val root = vm.getApplication<AutopilotApp>().workspaceRoot
    var currentDir by remember { mutableStateOf(root) }
    var editingPath by remember { mutableStateOf(false) }
    var pathText by remember { mutableStateOf(root.absolutePath) }
    var refreshKey by remember { mutableStateOf(0) }
    var granted by remember { mutableStateOf(StorageAccess.isFullAccess(context)) }
    var transferMsg by remember { mutableStateOf<String?>(null) }
    var viewingFile by remember { mutableStateOf<File?>(null) }
    var actionFile by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = StorageAccess.isFullAccess(context)
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val legacyPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        granted = StorageAccess.isFullAccess(context)
        refreshKey++
    }

    fun requestAccess() {
        if (StorageAccess.needsAllFilesSettings(context)) {
            StorageAccess.openAllFilesSettings(context)
        } else {
            legacyPermLauncher.launch(StorageAccess.legacyPermissions())
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) {
                dev.autopilot.terminal.data.WorkspaceTransfer.exportToUri(context, uri, root)
            }
            transferMsg = r.fold({ "已导出 $it 个文件" }, { "导出失败: ${it.message}" })
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val r = withContext(Dispatchers.IO) {
                dev.autopilot.terminal.data.WorkspaceTransfer.importFromUri(context, uri, root)
            }
            transferMsg = r.fold({ "已导入 $it 个文件到工作区" }, { "导入失败: ${it.message}" })
            refreshKey++
        }
    }

    val dirState by produceState(DirState(), currentDir, refreshKey) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val list = currentDir.listFiles()
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: return@withContext DirState(error = "无法读取该目录（权限不足或路径无效）")
                DirState(entries = list)
            }.getOrElse { DirState(error = "读取失败: ${it.message}") }
        }
    }

    Column(Modifier.fillMaxSize().background(TerminalBlack)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("文件", fontSize = 17.sp, color = TextMain, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                runCatching { exportLauncher.launch("autopilot-workspace.zip") }
                    .onFailure { transferMsg = "无法启动导出: ${it.message}" }
            }) { Text("导出 zip", fontSize = 12.sp) }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = {
                    runCatching { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }
                        .onFailure { transferMsg = "无法选择文件: ${it.message}" }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentPurple, contentColor = Color.Black)
            ) { Text("导入 zip", fontSize = 12.sp) }
        }

        if (!granted) {
            Surface(
                color = Color(0xFF2A2110),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("未开启存储权限", color = AccentAmber, fontSize = 13.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "开启「所有文件访问」后，可直接浏览并管理手机里的全部文件，免去每次选择器的麻烦",
                        color = GrayText, fontSize = 11.sp, lineHeight = 15.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = ::requestAccess) { Text("去开启", fontSize = 12.sp) }
                    }
                }
            }
        }

        Surface(
            color = TerminalSurface,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = {
                    currentDir.parentFile?.let { currentDir = it }
                }, enabled = currentDir.absolutePath != "/") {
                    Icon(Icons.Filled.ArrowUpward, "上级目录", tint = AccentGreen)
                }
                if (editingPath) {
                    OutlinedTextField(
                        value = pathText,
                        onValueChange = { pathText = it },
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = TextMain
                        ),
                        singleLine = true,
                        placeholder = { Text("/sdcard/...", fontSize = 11.sp, color = GrayText) }
                    )
                    TextButton(onClick = { editingPath = false }) { Text("取消", fontSize = 11.sp) }
                    TextButton(onClick = {
                        val target = File(pathText.trim()).absoluteFile
                        if (target.isDirectory && target.canRead()) {
                            currentDir = target
                            editingPath = false
                            refreshKey++
                        } else {
                            transferMsg = "目录不存在或不可读: ${pathText.trim()}"
                        }
                    }) { Text("跳转", fontSize = 11.sp, color = AccentGreen) }
                } else {
                    Text(
                        currentDir.absolutePath,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = AccentPurple,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable {
                            pathText = currentDir.absolutePath
                            editingPath = true
                        }
                    )
                    IconButton(onClick = {
                        pathText = currentDir.absolutePath
                        editingPath = true
                    }) { Icon(Icons.Filled.Edit, "编辑路径", tint = GrayText) }
                    IconButton(onClick = { currentDir = root; refreshKey++ }) {
                        Icon(Icons.Filled.Home, "回到工作区", tint = AccentPurple)
                    }
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Filled.Refresh, "刷新", tint = GrayText)
                    }
                }
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            modifier = Modifier.padding(vertical = 2.dp)
        ) {
            items(quickDirs(root)) { (label, dir) ->
                AssistChip(
                    onClick = {
                        if (dir.isDirectory && dir.canRead()) {
                            currentDir = dir
                            refreshKey++
                        } else {
                            transferMsg = "$label 不可访问"
                        }
                    },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = TerminalSurface,
                        labelColor = AccentGreen
                    ),
                    border = null
                )
            }
        }

        transferMsg?.let {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(it, fontSize = 11.sp, color = AccentAmber, modifier = Modifier.weight(1f))
                TextButton(onClick = { transferMsg = null }) { Text("知道了", fontSize = 10.sp) }
            }
        }

        LazyColumn(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Spacer(Modifier.height(0.dp)) }
            dirState.error?.let { err ->
                item {
                    Text(
                        err,
                        color = AccentAmber, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp)
                    )
                }
            }
            if (dirState.error == null && dirState.entries.isEmpty()) {
                item {
                    Text(
                        "空目录",
                        color = GrayText, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 16.dp)
                    )
                }
            }
            items(dirState.entries, key = { it.absolutePath }) { entry ->
                FileCard(
                    entry,
                    onOpenTerminal = { vm.requestOpenInTerminal(it) }
                ) {
                    when {
                        entry.isDirectory -> {
                            currentDir = entry
                            refreshKey++
                        }
                        else -> actionFile = entry
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    actionFile?.let { file ->
        AlertDialog(
            onDismissRequest = { actionFile = null },
            title = { Text(file.name, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text(file.absolutePath, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = GrayText)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${formatSize(file.length())} · ${formatTime(file.lastModified())}",
                        fontSize = 11.sp, color = GrayText
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        viewingFile = file
                        actionFile = null
                    }) { Text("查看") }
                    TextButton(onClick = {
                        val src = file
                        actionFile = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { copyToWorkspace(src, root) }
                            transferMsg = r.fold({ "已复制到工作区: $it" }, { "复制失败: ${it.message}" })
                            refreshKey++
                        }
                    }) { Text("复制到工作区", color = AccentGreen) }
                }
            },
            dismissButton = { TextButton(onClick = { actionFile = null }) { Text("取消") } }
        )
    }

    viewingFile?.let { file ->
        AlertDialog(
            onDismissRequest = { viewingFile = null },
            title = { Text(file.name, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Text(
                    remember(file.absolutePath) {
                        runCatching { file.readText().take(8000) }.getOrElse { "(无法读取: ${it.message})" }
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            },
            confirmButton = { TextButton(onClick = { viewingFile = null }) { Text("关闭") } }
        )
    }
}

@Composable
private fun FileCard(entry: File, onOpenTerminal: (File) -> Unit, onClick: () -> Unit) {
    Surface(
        color = CardColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (entry.isDirectory) AccentPurple else AccentGreen,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    color = if (entry.isDirectory) TextMain else TextDim,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    if (entry.isDirectory) {
                        val n = runCatching { entry.listFiles()?.size }.getOrNull()
                        "${n ?: "?"} 项 · ${formatTime(entry.lastModified())}"
                    } else {
                        "${formatSize(entry.length())} · ${formatTime(entry.lastModified())}"
                    },
                    color = GrayText, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.isDirectory && entry.canRead() && entry.canExecute()) {
                TextButton(onClick = { onOpenTerminal(entry) }) {
                    Text("终端", fontSize = 11.sp, color = AccentGreen)
                }
            } else {
                Icon(Icons.Filled.ChevronRight, null, tint = Color(0xFF4B4B60))
            }
        }
    }
}

private fun quickDirs(root: File): List<Pair<String, File>> = buildList {
    add("工作区" to root)
    Environment.getExternalStorageDirectory()?.let { add("内部存储" to it) }
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { add("下载" to it) }
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)?.let { add("文档" to it) }
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)?.let { add("图片" to it) }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private val timeFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

private fun formatTime(millis: Long): String =
    if (millis <= 0) "-" else timeFormat.format(Date(millis))

private fun copyToWorkspace(src: File, root: File): Result<String> = runCatching {
    require(src.isFile) { "不是普通文件" }
    var dest = File(root, src.name)
    var n = 1
    while (dest.exists()) {
        dest = File(root, "${src.nameWithoutExtension}_$n.${src.extension}")
        n++
    }
    src.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
    dest.name
}
