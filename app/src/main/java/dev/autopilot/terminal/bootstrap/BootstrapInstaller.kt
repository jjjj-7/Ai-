package dev.autopilot.terminal.bootstrap

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

class BootstrapInstaller private constructor(private val appContext: Context) {

    val prefix: File = File(appContext.filesDir, "usr")
    val homeDir: File = File(appContext.filesDir, "home")

    sealed class InstallState {
        data object Idle : InstallState()
        data object Installing : InstallState()
        data object Ready : InstallState()
        data class Failed(val reason: String) : InstallState()
    }

    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state

    suspend fun ensureInstalled() = withContext(Dispatchers.IO) {
        if (_state.value is InstallState.Ready) return@withContext
        _state.value = InstallState.Installing
        try {
            if (isReady()) {
                _state.value = InstallState.Ready
                return@withContext
            }
            installFromAssets()
            _state.value = if (isReady()) InstallState.Ready
            else InstallState.Failed("bootstrap 解压完成但 bash 缺失")
        } catch (t: Throwable) {
            _state.value = InstallState.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    fun isReady(): Boolean = runCatching { File(prefix, "bin/bash").canExecute() }.getOrDefault(false)

    fun envSpec(cwd: String): Array<String> {
        val path = System.getenv("PATH") ?: "/system/bin"
        return arrayOf(
            "PATH=${File(prefix, "bin").path}:$path",
            "PREFIX=${prefix.path}",
            "TMPDIR=${File(prefix, "tmp").path}",
            "HOME=${homeDir.path}",
            "LD_LIBRARY_PATH=${File(prefix, "lib").path}",
            "LANG=en_US.UTF-8",
            "TERM=xterm-256color"
        )
    }

    private fun archName(): String {
        val abis = Build.SUPPORTED_ABIS
        val preferred = listOf("arm64-v8a", "x86_64")
        for (want in preferred) for (have in abis) if (have == want) return want
        return abis.firstOrNull() ?: "arm64-v8a"
    }

    private fun installFromAssets() {
        val staging = File(appContext.filesDir, "staging-usr-tmp")
        staging.deleteRecursively()
        staging.mkdirs()

        val assetName = "bootstrap-${archName()}.zip"
        appContext.assets.open(assetName).use { input ->
            ZipInputStream(input.buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val target = File(staging, entry.name)
                    if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator) &&
                        target.canonicalPath != staging.canonicalPath
                    ) continue
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        java.io.FileOutputStream(target).use { out -> zis.copyTo(out) }
                        if (entry.name.startsWith("bin/") ||
                            entry.name.startsWith("libexec") ||
                            entry.name.startsWith("lib/apt/apt-helper") ||
                            entry.name.startsWith("lib/apt/methods")
                        ) {
                            runCatching { Os.chmod(target.absolutePath, 448) }
                        }
                    }
                    zis.closeEntry()
                }
            }
        }

        val symlinksFile = File(staging, "SYMLINKS.txt")
        if (!symlinksFile.isFile) throw RuntimeException("SYMLINKS.txt 缺失")
        symlinksFile.useLines { lines ->
            lines.forEach { line ->
                val parts = line.split("←")
                if (parts.size == 2) {
                    val linkPath = File(staging, parts[1].trim())
                    linkPath.parentFile?.mkdirs()
                    runCatching { linkPath.delete() }
                    runCatching { Os.symlink(parts[0].trim(), linkPath.absolutePath) }
                }
            }
        }

        if (prefix.exists()) prefix.deleteRecursively()
        if (!staging.renameTo(prefix)) throw RuntimeException("staging 重命名失败")
        File(prefix, "tmp").mkdirs()
        homeDir.mkdirs()
    }

    companion object {
        @Volatile private var instance: BootstrapInstaller? = null

        fun get(app: Context): BootstrapInstaller =
            instance ?: synchronized(this) {
                instance ?: BootstrapInstaller(app.applicationContext).also { instance = it }
            }
    }
}
