package dev.autopilot.terminal.llm

import dev.autopilot.terminal.data.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Duration

data class ChatMessage(
    val role: String,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null
)

data class ToolCall(
    val id: String,
    val function: String,
    val arguments: String
)

data class ToolDefinition(
    val type: String = "function",
    val function: ToolFunction
)

data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

sealed class LlmEvent {
    data class Delta(val text: String, val toolCalls: List<PartialToolCall> = emptyList()) : LlmEvent()
    data class Completed(val fullText: String, val toolCalls: List<ToolCall> = emptyList()) : LlmEvent()
    data class Failed(val error: String, val retriable: Boolean) : LlmEvent()
}

data class PartialToolCall(
    val index: Int,
    val id: String = "",
    val function: String = "",
    val argumentsFragment: String = ""
)

class LlmException(message: String, val retriable: Boolean) : IOException(message)

class LlmClient(
    private val configProvider: () -> ModelConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(20))
        .readTimeout(Duration.ofMinutes(5))
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun chat(
        messages: List<ChatMessage>,
        maxTokens: Int = 8192,
        tools: List<ToolDefinition> = emptyList()
    ): Flow<LlmEvent> = callbackFlow {
        val cfg = configProvider()
        if (!cfg.isComplete()) {
            trySend(LlmEvent.Failed("模型配置未完成：请先在设置中填写 API 地址、密钥与模型名", false))
            close()
            return@callbackFlow
        }
        val body = buildRequest(cfg, messages, maxTokens, tools).toString()
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
                    val toolCallAcc = mutableMapOf<Int, PartialToolCall>()

                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]") break

                        val (textDelta, toolDeltas) = extractDelta(payload)
                        if (textDelta.isNotEmpty()) {
                            sb.append(textDelta as CharSequence)
                        }
                        if (toolDeltas.isNotEmpty()) {
                            for (td in toolDeltas) {
                                val existing = toolCallAcc[td.index] ?: PartialToolCall(td.index)
                                val merged = PartialToolCall(
                                    index = td.index,
                                    id = if (td.id.isNotEmpty()) td.id else existing.id,
                                    function = if (td.function.isNotEmpty()) td.function else existing.function,
                                    argumentsFragment = existing.argumentsFragment + td.argumentsFragment
                                )
                                toolCallAcc[td.index] = merged
                            }
                            trySend(LlmEvent.Delta(textDelta, toolCallAcc.values.toList()))
                        } else if (textDelta.isNotEmpty()) {
                            trySend(LlmEvent.Delta(textDelta))
                        }
                    }

                    val finalToolCalls = toolCallAcc.values
                        .sortedBy { it.index }
                        .filter { it.function.isNotEmpty() || it.argumentsFragment.isNotEmpty() }
                        .map { ToolCall(
                            id = it.id.ifBlank { "call_${it.index}" },
                            function = it.function,
                            arguments = it.argumentsFragment
                        )}

                    trySend(LlmEvent.Completed(sb.toString(), finalToolCalls))
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

    private fun extractDelta(payload: String): Pair<String, List<PartialToolCall>> {
        return runCatching {
            val obj = json.parseToJsonElement(payload).jsonObject
            val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching Pair("", emptyList())
            val delta = choice["delta"]?.jsonObject ?: return@runCatching Pair("", emptyList())

            val textDelta = delta["content"]?.let { el ->
                when {
                    el is JsonNull -> ""
                    el is JsonPrimitive && el.isString -> el.content
                    else -> ""
                }
            } ?: ""

            val toolDeltas = delta["tool_calls"]?.jsonArray?.map { tcEl ->
                val tc = tcEl.jsonObject
                val idx = tc["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val id = tc["id"]?.jsonPrimitive?.content ?: ""
                val fn = tc["function"]?.jsonObject
                val fnName = fn?.get("name")?.jsonPrimitive?.content ?: ""
                val fnArgs = fn?.get("arguments")?.jsonPrimitive?.content ?: ""
                PartialToolCall(idx, id, fnName, fnArgs)
            } ?: emptyList()

            Pair(textDelta, toolDeltas)
        }.getOrDefault(Pair("", emptyList()))
    }

    private fun buildRequest(
        cfg: ModelConfig,
        messages: List<ChatMessage>,
        maxTokens: Int,
        tools: List<ToolDefinition>
    ): JsonObject = buildJsonObject {
        put("model", cfg.model)
        put("temperature", cfg.temperature)
        put("max_tokens", maxTokens)
        put("stream", true)
        putJsonArray("messages") {
            messages.forEach { m ->
                add(buildJsonObject {
                    put("role", m.role)
                    if (m.content.isNotEmpty()) {
                        put("content", m.content)
                    }
                    if (m.toolCalls.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            m.toolCalls.forEach { tc ->
                                add(buildJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", tc.function)
                                        put("arguments", tc.arguments)
                                    })
                                })
                            }
                        }
                    }
                    if (m.toolCallId != null) {
                        put("tool_call_id", m.toolCallId)
                    }
                    if (m.name != null) {
                        put("name", m.name)
                    }
                    if (m.content.isEmpty() && m.toolCalls.isEmpty() && m.toolCallId == null) {
                        put("content", "")
                    }
                })
            }
        }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.forEach { td ->
                    add(buildJsonObject {
                        put("type", td.type)
                        put("function", buildJsonObject {
                            put("name", td.function.name)
                            put("description", td.function.description)
                            put("parameters", td.function.parameters)
                        })
                    })
                }
            }
            put("tool_choice", "auto")
        }
    }
}
