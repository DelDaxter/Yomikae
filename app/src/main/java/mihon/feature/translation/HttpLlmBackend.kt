package mihon.feature.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import java.util.concurrent.TimeUnit

/**
 * Yomikae: LLM served over the OpenAI-compatible chat API (`POST /v1/chat/completions`).
 * Works with `llama-server` from llama.cpp running on a PC, with Ollama, LM Studio, and with
 * hosted services. Kept as an optional path (test bench, future plugin); the embedded model is
 * the default target.
 */
class HttpLlmBackend(
    private val serverUrl: String,
    private val model: String,
) : LlmBackend {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val endpoint: String
        get() = serverUrl.trimEnd('/') + "/v1/chat/completions"

    override suspend fun prepare() {
        if (serverUrl.isBlank()) error("No LLM server address set (Settings > Translation)")
        // A tiny request so a wrong address fails fast, before any OCR work is done.
        complete("Reply with the single word OK.")
    }

    override suspend fun complete(prompt: String): String = withIOContext {
        val body = buildJsonObject {
            put("model", model.ifBlank { "default" })
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", prompt)
                        },
                    )
                },
            )
            put("temperature", LlmTranslator.TEMPERATURE)
            put("top_p", LlmTranslator.TOP_P)
            put("top_k", LlmTranslator.TOP_K)
            put("repeat_penalty", 1.05)
            put("max_tokens", LlmTranslator.MAX_OUTPUT_TOKENS)
            put("stream", false)
        }
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) error("LLM server ${response.code}: ${text.take(200)}")
            json.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0]
                .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
