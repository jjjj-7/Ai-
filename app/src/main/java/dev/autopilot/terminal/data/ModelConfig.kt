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

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "model_config",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun load(): ModelConfig {
        val raw = prefs.getString(KEY, null) ?: return ModelConfig()
        return runCatching { Json.decodeFromString<ModelConfig>(raw) }.getOrDefault(ModelConfig())
    }

    override fun save(config: ModelConfig) {
        prefs.edit().putString(KEY, Json.encodeToString(ModelConfig.serializer(), config)).apply()
    }

    private companion object {
        const val KEY = "config_json"
    }
}
