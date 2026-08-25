package dev.autopilot.terminal.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class TerminalSessionState(
    val session: PtySession,
    val buffer: TerminalBuffer
)

class SessionRegistry(
    private val envProvider: () -> ShellEnvSpec,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _sessions = MutableStateFlow<List<TerminalSessionState>>(emptyList())
    val sessions: StateFlow<List<TerminalSessionState>> = _sessions

    fun create(name: String): TerminalSessionState? {
        if (_sessions.value.any { it.session.name == name }) return null
        val spec = envProvider()
        val pty = PtySession(name, spec.shellPath, spec.argv, spec.envp, spec.cwd, cols = spec.cols, rows = spec.rows)
        if (pty.pid <= 0) return null
        val buffer = TerminalBuffer()
        val state = TerminalSessionState(pty, buffer)
        scope.launch {
            pty.output.collect { chunk -> buffer.process(chunk) }
        }
        _sessions.value = _sessions.value + state
        return state
    }

    fun close(name: String) {
        val target = _sessions.value.firstOrNull { it.session.name == name } ?: return
        target.session.close()
        _sessions.value = _sessions.value.filterNot { it.session.name == name }
    }

    fun closeAll() {
        _sessions.value.forEach { it.session.close() }
        _sessions.value = emptyList()
    }

    fun byName(name: String): TerminalSessionState? =
        _sessions.value.firstOrNull { it.session.name == name }
}

data class ShellEnvSpec(
    val shellPath: String,
    val argv: List<String>,
    val envp: List<String>,
    val cwd: String,
    val cols: Int = 100,
    val rows: Int = 30
)
