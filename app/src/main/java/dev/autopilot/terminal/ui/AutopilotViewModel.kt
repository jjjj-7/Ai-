package dev.autopilot.terminal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.autopilot.terminal.bootstrap.BootstrapInstaller
import dev.autopilot.terminal.data.EncryptedConfigStore
import dev.autopilot.terminal.data.ModelConfig
import dev.autopilot.terminal.agent.AgentEngine
import dev.autopilot.terminal.llm.LlmClient
import dev.autopilot.terminal.perms.PtyChannel
import dev.autopilot.terminal.perms.PermissionManager
import dev.autopilot.terminal.terminal.SessionRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AutopilotViewModel(app: Application) : AndroidViewModel(app) {

    val configStore = EncryptedConfigStore(app)
    val installer = BootstrapInstaller.get(app)
    val perms = PermissionManager(app)
    val registry = SessionRegistry(
        envProvider = {
            val cwd = (app as dev.autopilot.terminal.AutopilotApp).workspaceRoot
            installer.envSpec(cwd)
        }
    )
    val llm = LlmClient(configProvider = { configStore.load() })
    val db = dev.autopilot.terminal.data.AppDatabase.get(app)

    private var ptyChannel: PtyChannel? = null

    val engine = AgentEngine(
        scope = viewModelScope,
        llm = llm,
        db = db,
        channelProvider = {
            runCatching { ensureChannel() }
            ptyChannel?.takeIf { it.kind == dev.autopilot.terminal.perms.ChannelKind.PTY }
        },
        channelDescProvider = { runCatching { perms.channelDescription() }.getOrDefault("通道检测中") }
    )

    private val _riskAccepted = MutableStateFlow(false)
    val riskAccepted: StateFlow<Boolean> = _riskAccepted

    private val _config = MutableStateFlow(configStore.load())
    val config: StateFlow<ModelConfig> = _config

    init {
        viewModelScope.launch {
            runCatching { ensureBootstrap() }
                .onFailure { android.util.Log.e(TAG, "bootstrap failed", it) }
        }
    }

    fun acceptRisk() {
        _riskAccepted.value = true
    }

    fun saveConfig(cfg: ModelConfig) {
        configStore.save(cfg)
        _config.value = cfg
    }

    fun retryBootstrap() {
        viewModelScope.launch {
            runCatching { ensureBootstrap() }
        }
    }

    private suspend fun ensureBootstrap() {
        if (installer.isReady()) return
        installer.ensureInstalled()
    }

    private fun ensureChannel() {
        if (ptyChannel != null) return
        val session = registry.byName(AGENT_SESSION)?.session
            ?: registry.create(AGENT_SESSION)?.session
            ?: return
        ptyChannel = PtyChannel(session, dev.autopilot.terminal.perms.CommandRunner(viewModelScope), viewModelScope)
    }

    fun submitTask(goal: String, criteria: List<String>) {
        engine.submit(goal, criteria, _config.value.maxIterations)
    }

    fun engineReset() {
        engine.reset()
    }

    companion object {
        private const val TAG = "AutopilotVM"
        const val AGENT_SESSION = "agent"
    }
}
