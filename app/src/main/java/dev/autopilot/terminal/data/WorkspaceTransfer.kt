package dev.autopilot.terminal.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object WorkspaceTransfer {

    fun exportToUri(context: Context, uri: Uri, workspaceRoot: File): Result<Int> = runCatching {
        var count = 0
        context.contentResolver.openOutputStream(uri)?.use { rawOut ->
            ZipOutputStream(rawOut.buffered()).use { zos ->
                workspaceRoot.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeToOrNull(workspaceRoot)?.invariantSeparatorsPath ?: return@forEach
                    zos.putNextEntry(ZipEntry(rel))
                    FileInputStream(file).copyTo(zos)
                    zos.closeEntry()
                    count++
                }
            }
        } ?: error("无法打开导出目标")
        count
    }

    fun importFromUri(context: Context, uri: Uri, workspaceRoot: File): Result<Int> = runCatching {
        var count = 0
        context.contentResolver.openInputStream(uri)?.use { rawIn ->
            ZipInputStream(rawIn.buffered()).use { zis ->
                val canonicalRoot = workspaceRoot.canonicalPath + File.separator
                while (true) {
                    val entry: ZipEntry = zis.nextEntry ?: break
                    if (entry.name.contains("..")) { zis.closeEntry(); continue }
                    val dest = File(workspaceRoot, entry.name)
                    if (!dest.canonicalPath.startsWith(canonicalRoot)) { zis.closeEntry(); continue }
                    if (entry.isDirectory) {
                        dest.mkdirs()
                    } else {
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { out -> zis.copyTo(out) }
                        count++
                    }
                    zis.closeEntry()
                }
            }
        } ?: error("无法读取所选文件")
        count
    }

    fun exportToDownloads(context: Context, workspaceRoot: File): Result<File> = runCatching {
        val downloads = File(System.getenv("EXTERNAL_STORAGE") ?: "/sdcard", "Download")
        downloads.mkdirs()
        val target = File(downloads, "autopilot-workspace-${System.currentTimeMillis()}.zip")
        FileOutputStream(target).use { fos ->
            ZipOutputStream(fos.buffered()).use { zos ->
                workspaceRoot.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeToOrNull(workspaceRoot)?.invariantSeparatorsPath ?: return@forEach
                    zos.putNextEntry(ZipEntry(rel))
                    FileInputStream(file).copyTo(zos)
                    zos.closeEntry()
                }
            }
        }
        target
    }

    fun importFromZip(context: Context, zipFile: File, workspaceRoot: File): Result<Int> = runCatching {
        var count = 0
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            val canonicalRoot = workspaceRoot.canonicalPath + File.separator
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                if (entry.name.contains("..")) { zis.closeEntry(); continue }
                val dest = File(workspaceRoot, entry.name)
                if (!dest.canonicalPath.startsWith(canonicalRoot)) { zis.closeEntry(); continue }
                if (entry.isDirectory) {
                    dest.mkdirs()
                } else {
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { out -> zis.copyTo(out) }
                    count++
                }
                zis.closeEntry()
            }
        }
        count
    }
}
