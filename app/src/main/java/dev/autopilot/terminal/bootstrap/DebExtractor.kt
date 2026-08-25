package dev.autopilot.terminal.bootstrap

import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileInputStream

object DebExtractor {

    fun extract(deb: File, prefixDir: File) {
        FileInputStream(deb).use { fin ->
            ArArchiveInputStream(fin).use { ar ->
                while (true) {
                    val entry = ar.nextArEntry ?: break
                    if (entry.name.startsWith("data.tar")) {
                        extractDataTar(ar, prefixDir)
                        return
                    }
                }
                error("no data.tar member in ${deb.name}")
            }
        }
    }

    private fun extractDataTar(raw: java.io.InputStream, prefixDir: File) {
        var compressed = raw as? java.io.InputStream
        XZCompressorInputStream(raw).use { xz ->
            TarArchiveInputStream(xz).use { tar ->
                while (true) {
                    val e = tar.nextTarEntry ?: break
                    val rel = e.name.removePrefix("./")
                    if (rel.isBlank()) continue
                    val target = File(prefixDir, sanitize(rel))
                    when {
                        e.isDirectory -> target.mkdirs()
                        e.isSymbolicLink -> createSymlink(target, e.linkName)
                        else -> {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> tar.copyTo(out) }
                            chmodExecutable(e, target)
                        }
                    }
                }
            }
        }
    }

    private fun createSymlink(target: File, linkName: String) {
        target.parentFile?.mkdirs()
        if (target.exists()) return
        android.system.Os.symlink(linkName, target.absolutePath)
    }

    private fun chmodExecutable(entry: TarArchiveEntry, target: File) {
        if (entry.mode and 0b001000000 != 0) {
            runCatching { android.system.Os.chmod(target.absolutePath, entry.mode and 0xFFF) }
        }
    }

    private fun sanitize(rel: String): String =
        rel.split('/').filter { it != ".." && it.isNotBlank() }.joinToString("/")
}
