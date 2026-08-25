package dev.autopilot.terminal.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.autopilot.terminal.bootstrap.BootstrapInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class TermuxSessionState(
    val name: String,
    val session: TerminalSession,
    val interactive: Boolean
)

class SessionRegistry(
    private val installer: BootstrapInstaller,
    private val workspaceRoot: File
) : TerminalSessionClient {

    private val _sessions = MutableStateFlow<List<TermuxSessionState>>(emptyList())
    val sessions: StateFlow<List<TermuxSessionState>> = _sessions

    @Volatile var onOutput: (() -> Unit)? = null
    @Volatile var onFinished: ((TerminalSession) -> Unit)? = null

    fun createInteractive(name: String): TermuxSessionState? {
        if (!installer.isReady()) return null
        val existing = byName(name)
        if (existing != null && existing.session.isRunning) return existing

        val bash = File(installer.prefix, "bin/bash")
        val session = TerminalSession(
            bash.absolutePath,
            workspaceRoot.absolutePath,
            arrayOf("-l"),
            installer.envSpec(workspaceRoot.absolutePath),
            null,
            this
        )
        session.initializeEmulator(DEFAULT_COLS, DEFAULT_ROWS)
        val state = TermuxSessionState(name, session, interactive = true)
        _sessions.value = _sessions.value
            .filter { it.name != name && it.session.isRunning }
            .filter { it.interactive || it.session.isRunning } + state
        return state
    }

    fun pruneDead() {
        _sessions.value = _sessions.value.filter { it.session.isRunning }
    }

    fun createOnce(name: String, shellArgs: Array<String>, cwd: String): TerminalSession? {
        if (!installer.isReady()) return null
        val sh = File(installer.prefix, "bin/sh")
        val session = TerminalSession(
            sh.absolutePath,
            cwd,
            shellArgs,
            installer.envSpec(cwd),
            null,
            this
        )
        session.initializeEmulator(DEFAULT_COLS, 200)
        return session
    }

    fun byName(name: String): TermuxSessionState? = _sessions.value.firstOrNull { it.name == name }

    override fun onTextChanged(changedSession: TerminalSession) {
        onOutput?.invoke()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        onFinished?.invoke(finishedSession)
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
    override fun onPasteTextFromClipboard(session: TerminalSession) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}

    companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
    }
}
