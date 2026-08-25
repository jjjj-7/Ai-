package dev.autopilot.terminal.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ModelConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.2,
    val maxIterations: Int = 50
) {
    fun isComplete(): Boolean =
        baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    fun masked(): ModelConfig = copy(apiKey = if (apiKey.isBlank()) "" else "sk-***")
}

interface ConfigStore {
    fun load(): ModelConfig
    fun save(config: ModelConfig)
}

class EncryptedConfigStore(context: Context) : ConfigStore {

    private val appContext = context.applicationContext

    private val prefs by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "model_config",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            appContext.getSharedPreferences("model_config_fallback", Context.MODE_PRIVATE)
        }
    }

    override fun load(): ModelConfig {
        return try {
            val raw = prefs.getString(KEY, null) ?: return ModelConfig()
            runCatching { Json.decodeFromString<ModelConfig>(raw) }.getOrDefault(ModelConfig())
        } catch (t: Throwable) {
            ModelConfig()
        }
    }

    override fun save(config: ModelConfig) {
        runCatching {
            prefs.edit().putString(KEY, Json.encodeToString(ModelConfig.serializer(), config)).apply()
        }
    }

    private companion object {
        const val KEY = "config_json"
    }
}
