package dev.autopilot.terminal.llm

import dev.autopilot.terminal.data.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Duration

data class ChatMessage(val role: String, val content: String)

sealed class LlmEvent {
    data class Delta(val text: String) : LlmEvent()
    data class Completed(val fullText: String) : LlmEvent()
    data class Failed(val error: String, val retriable: Boolean) : LlmEvent()
}

class LlmException(message: String, val retriable: Boolean) : IOException(message)

class LlmClient(
    private val configProvider: () -> ModelConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(20))
        .readTimeout(Duration.ofMinutes(5))
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun chat(messages: List<ChatMessage>, maxTokens: Int = 4096): Flow<LlmEvent> = callbackFlow {
        val cfg = configProvider()
        if (!cfg.isComplete()) {
            trySend(LlmEvent.Failed("模型配置未完成：请先在设置中填写 API 地址、密钥与模型名", false))
            close()
            return@callbackFlow
        }
        val body = buildRequest(cfg, messages, maxTokens).toString()
        val request = Request.Builder()
            .url("${cfg.baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        var attempt = 0
        val maxAttempts = 4

        suspend fun attemptOnce(): Boolean {
            try {
                client.newCall(request).execute().use { resp ->
                    when {
                        resp.code == 429 -> throw LlmException(retryAfterMessage(resp), true)
                        resp.code >= 500 -> throw LlmException("服务端错误 HTTP ${resp.code}", true)
                        !resp.isSuccessful -> throw LlmException("请求失败 HTTP ${resp.code}: ${resp.body?.string()?.take(400)}", false)
                    }
                    val source = resp.body?.source() ?: error("empty response body")
                    val sb = StringBuilder()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        val delta = extractDelta(payload)
                        if (delta.isNotEmpty()) {
                            sb.append(delta)
                            trySend(LlmEvent.Delta(delta))
                        }
                    }
                    trySend(LlmEvent.Completed(sb.toString()))
                }
                return true
            } catch (e: LlmException) {
                if (attempt < maxAttempts && e.retriable) {
                    backoff(attempt)
                    return false
                }
                trySend(LlmEvent.Failed(e.message ?: "unknown llm error", e.retriable))
                return true
            } catch (e: Exception) {
                if (attempt < maxAttempts && isNetworkError(e)) {
                    backoff(attempt)
                    return false
                }
                trySend(LlmEvent.Failed(e.message ?: "network error", isNetworkError(e)))
                return true
            }
        }

        while (true) {
            attempt++
            if (attemptOnce()) break
        }
        close()
    }.flowOn(Dispatchers.IO)

    private fun retryAfterMessage(resp: Response): String {
        val waitSec = resp.header("Retry-After")?.toLongOrNull() ?: 5L
        return "RATE_LIMIT:$waitSec"
    }

    private suspend fun backoff(attempt: Int) {
        kotlinx.coroutines.delay((1L shl attempt.coerceAtMost(5)) * 1000)
    }

    private fun isNetworkError(e: Exception): Boolean =
        e is IOException && e !is LlmException || (e.message?.startsWith("RATE_LIMIT") == true)

    private fun extractDelta(payload: String): String = runCatching {
        val obj = json.parseToJsonElement(payload).jsonObject
        obj["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("delta")?.jsonObject?.get("content")
            ?.let { el ->
                when {
                    el is kotlinx.serialization.json.JsonNull -> ""
                    el is kotlinx.serialization.json.JsonPrimitive && el.isString -> el.content
                    else -> el.toString()
                }
            }
            ?: ""
    }.getOrDefault("")

    private fun buildRequest(cfg: ModelConfig, messages: List<ChatMessage>, maxTokens: Int): JsonObject =
        buildJsonObject {
            put("model", cfg.model)
            put("temperature", cfg.temperature)
            put("max_tokens", maxTokens)
            put("stream", true)
            putJsonArray("messages") {
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            }
        }
}
