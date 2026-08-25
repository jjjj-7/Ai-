package dev.autopilot.terminal.ui.files

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
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autopilot.terminal.ui.AutopilotViewModel
import java.io.File

@Composable
fun FileTreeScreen(vm: AutopilotViewModel) {
    val root = (vm.getApplication<dev.autopilot.terminal.AutopilotApp>()).workspaceRoot
    var currentDir by remember { mutableStateOf(root) }
    var viewingFile by remember { mutableStateOf<File?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                currentDir.relativeToOrNull(root)?.path ?: "/",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = Color(0xFF9CA3AF),
                modifier = Modifier.weight(1f)
            )
            if (currentDir != root) {
                OutlinedButton(onClick = {
                    currentDir = currentDir.parentFile ?: root
                }) { Text("上级") }
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            items(
                currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: emptyList()
            ) { entry ->
                ListItem(
                    headlineContent = { Text(entry.name, fontSize = 14.sp) },
                    leadingContent = {
                        if (entry.isDirectory) Icon(Icons.Filled.Folder, null, tint = dev.autopilot.terminal.ui.AccentPurple)
                        else Text("·", fontSize = 18.sp)
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
