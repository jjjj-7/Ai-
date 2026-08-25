package dev.autopilot.terminal.bootstrap

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class BootstrapInstaller(
    private val context: Context,
    private val source: BootstrapSource = TermuxRepoSource(),
    private val packages: List<String> = DEFAULT_PACKAGES
) {
    val prefixDir: File get() = File(context.filesDir, "usr")
    private val cacheDir: File get() = File(context.cacheDir, "debs")

    sealed class InstallState {
        data object Idle : InstallState()
        data class Downloading(val pkg: String, val index: Int, val total: Int) : InstallState()
        data object Extracting : InstallState()
        data object Ready : InstallState()
        data class Failed(val reason: String) : InstallState()
    }

    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state

    private val mutex = Mutex()

    suspend fun ensureInstalled(): Result<File> = mutex.withLock {
        if (isReady()) return Result.success(prefixDir)
        withContext(Dispatchers.IO) { installInternal() }
    }

    fun isReady(): Boolean =
        File(prefixDir, "bin/busybox").exists() && File(prefixDir, "bin/bash").exists()

    private suspend fun installInternal(): Result<File> = runCatching {
        prefixDir.mkdirs()
        cacheDir.mkdirs()
        packages.forEachIndexed { i, pkg ->
            _state.value = InstallState.Downloading(pkg, i + 1, packages.size)
            val deb = File(cacheDir, "$pkg.deb")
            if (!deb.exists()) source.fetch(pkg, deb).getOrThrow()
        }
        _state.value = InstallState.Extracting
        packages.forEach { pkg ->
            val deb = File(cacheDir, "$pkg.deb")
            DebExtractor.extract(deb, prefixDir)
        }
        writeEnvScript()
        check(isReady()) { "bootstrap extraction finished but core binaries missing" }
        _state.value = InstallState.Ready
        prefixDir
    }.onFailure { _state.value = InstallState.Failed(it.message ?: "unknown") }

    fun envSpec(cwd: File, cols: Int = 100, rows: Int = 30): BootstrapEnvSpec {
        val bin = File(prefixDir, "bin").absolutePath
        val tmp = File(prefixDir, "tmp").apply { mkdirs() }.absolutePath
        val home = cwd.absolutePath
        val envp = listOf(
            "PATH=$bin:/system/bin:/system/xbin",
            "HOME=$home",
            "PREFIX=$prefixDir",
            "TMPDIR=$tmp",
            "LD_LIBRARY_PATH=${File(prefixDir, "lib")}",
            "LANG=en_US.UTF-8",
            "TERM=xterm-256color"
        )
        return BootstrapEnvSpec(
            shellPath = File(bin, "bash").takeIf { it.exists() }?.absolutePath ?: "/system/bin/sh",
            argv = listOf("-l"),
            envp = envp,
            cwd = home,
            cols = cols,
            rows = rows
        )
    }

    private fun writeEnvScript() {
        File(prefixDir, "etc/profile.d/autopilot.sh").parentFile?.mkdirs()
        File(prefixDir, "etc/profile.d/autopilot.sh").writeText("export PATH=\$PREFIX/bin:\$PATH\n")
    }

    companion object {
        val DEFAULT_PACKAGES = listOf(
            "busybox", "coreutils", "bash", "libandroid-support",
            "ncurses-utils", "readline", "git", "clang", "python", "nodejs"
        )

        @Volatile private var instance: BootstrapInstaller? = null

        fun get(context: Context): BootstrapInstaller = instance ?: synchronized(this) {
            instance ?: BootstrapInstaller(context.applicationContext).also { instance = it }
        }
    }
}

typealias BootstrapEnvSpec = dev.autopilot.terminal.terminal.ShellEnvSpec
