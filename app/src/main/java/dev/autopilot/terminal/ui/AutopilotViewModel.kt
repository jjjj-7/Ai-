package dev.autopilot.terminal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.autopilot.terminal.bootstrap.BootstrapInstaller
import dev.autopilot.terminal.TaskGuardService
import dev.autopilot.terminal.data.EncryptedConfigStore
import dev.autopilot.terminal.data.ModelConfig
import dev.autopilot.terminal.agent.AgentEngine
import dev.autopilot.terminal.llm.LlmClient
import dev.autopilot.terminal.perms.TermuxChannel
import dev.autopilot.terminal.terminal.SessionRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class AutopilotViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = app as dev.autopilot.terminal.AutopilotApp

    val configStore = EncryptedConfigStore(app)
    val installer = BootstrapInstaller.get(app)
    val registry = SessionRegistry(installer, appCtx.workspaceRoot)
    val channel = TermuxChannel(registry, appCtx.appScope)
    val llm = LlmClient(configProvider = { configStore.load() })
    val db = dev.autopilot.terminal.data.AppDatabase.get(app)

    val engine = AgentEngine(
        scope = appCtx.appScope,
        llm = llm,
        db = db,
        channelProvider = { channel.takeIf { installer.isReady() } },
        channelDescProvider = {
            if (installer.isReady()) "Termux 用户态 / 完整工具链" else "环境安装中"
        }
    )

    init {
        channel.bindWorkspace { appCtx.workspaceRoot.absolutePath }
        appCtx.appScope.launch {
            engine.busy.collect { busy ->
                if (busy) TaskGuardService.start(app)
                else TaskGuardService.stop(app)
            }
        }
        viewModelScope.launch {
            runCatching { ensureBootstrap() }
                .onSuccess { if (installer.isReady()) preinstallPythonDeps() }
                .onFailure { android.util.Log.e(TAG, "bootstrap failed", it) }
        }
    }

    private val _riskAccepted = MutableStateFlow(false)
    val riskAccepted: StateFlow<Boolean> = _riskAccepted

    val openTerminalAt = MutableStateFlow<File?>(null)
    val navigateTab = MutableStateFlow<String?>(null)

    fun requestOpenInTerminal(dir: File) {
        navigateTab.value = "terminal"
        openTerminalAt.value = dir
    }

    private val _config = MutableStateFlow(configStore.load())
    val config: StateFlow<ModelConfig> = _config

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

    private fun preinstallPythonDeps() {
        appCtx.appScope.launch {
            runCatching {
                registry.createOnce(
                    "pip-preload",
                    arrayOf("sh", "-c", "pip install -q requests beautifulsoup4 lxml 2>/dev/null; echo pip-done"),
                    appCtx.workspaceRoot.absolutePath
                )
            }
        }
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
