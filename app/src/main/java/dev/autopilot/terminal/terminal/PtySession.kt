package dev.autopilot.terminal.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PtySession(
    val name: String,
    cmd: String,
    argv: List<String>,
    envp: List<String>,
    cwd: String,
    cols: Int = 80,
    rows: Int = 24
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pidHolder = IntArray(1)
    @Volatile private var masterFd: Int = -1
    val pid: Int get() = pidHolder.getOrElse(0) { -1 }

    private val _output = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024)
    val output: SharedFlow<ByteArray> = _output

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode

    init {
        if (!PtyJni.available) {
            _exitCode.value = -1
            _output.tryEmit("[native] PTY 库不可用：当前设备 ABI 不受支持".toByteArray())
        } else {
            masterFd = PtyJni.forkPty(cmd, argv.toTypedArray(), envp.toTypedArray(), cwd, pidHolder)
            if (masterFd >= 0) {
                PtyJni.resize(masterFd, cols, rows)
                startReader()
                watchExit()
            } else {
                _exitCode.value = -1
            }
        }
    }

    fun isAlive(): Boolean = exitCode.value == null && masterFd >= 0

    private fun startReader() {
        scope.launch {
            val buf = ByteArray(8192)
            while (isActive && masterFd >= 0) {
                val fd = masterFd
                if (fd < 0) break
                val n = runCatching { PtyJni.readFd(fd, buf) }.getOrDefault(-1)
                when {
                    n > 0 -> _output.emit(buf.copyOf(n))
                    n == 0 -> delay(20)
                    else -> break
                }
            }
        }
    }

    private fun watchExit() {
        scope.launch {
            while (isActive && _exitCode.value == null) {
                val code = runCatching { PtyJni.waitFor(pid, 100) }.getOrDefault(-1)
                if (code == -2 || code == -1) continue
                _exitCode.value = code
            }
            val fd = masterFd
            if (fd >= 0) {
                PtyJni.closeFd(fd)
                masterFd = -1
            }
        }
    }

    suspend fun write(data: ByteArray): Boolean {
        val fd = masterFd
        if (fd < 0) return false
        return runCatching { PtyJni.writeFd(fd, data, data.size) > 0 }.getOrDefault(false)
    }

    suspend fun writeLine(line: String): Boolean =
        write((line.removeSuffix("\n") + "\n").toByteArray(Charsets.UTF_8))

    fun resize(cols: Int, rows: Int) {
        val fd = masterFd
        if (fd >= 0) PtyJni.resize(fd, cols, rows)
    }

    fun close() {
        if (_exitCode.value == null && pid > 0) PtyJni.killProcess(pid)
        val fd = masterFd
        if (fd >= 0) {
            PtyJni.closeFd(fd)
            masterFd = -1
        }
        scope.coroutineContext[Job]?.cancelChildren()
    }
}
