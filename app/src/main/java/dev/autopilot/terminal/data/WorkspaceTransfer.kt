package dev.autopilot.terminal.data

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object WorkspaceTransfer {

    fun exportToDownloads(context: Context, workspaceRoot: File): Result<File> = runCatching {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            error("需要所有文件访问权限才能写入下载目录")
        }
        downloads.mkdirs()
        val target = File(downloads, "autopilot-workspace-${System.currentTimeMillis()}.zip")
        FileOutputStream(target).use { fos -> zipDir(workspaceRoot, fos, workspaceRoot) }
        target
    }

    fun importFromZip(context: Context, zipFile: File, workspaceRoot: File): Result<Int> = runCatching {
        var count = 0
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            val canonicalRoot = workspaceRoot.canonicalPath + File.separator
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                val dest = File(workspaceRoot, entry.name)
                if (!dest.canonicalPath.startsWith(canonicalRoot)) continue
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

    private fun zipDir(dir: File, out: FileOutputStream, root: File) {
        ZipOutputStream(out.buffered()).use { zos ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeToOrNull(root)?.invariantSeparatorsPath ?: return@forEach
                zos.putNextEntry(ZipEntry(rel))
                FileInputStream(file).copyTo(zos)
                zos.closeEntry()
            }
        }
    }
}
