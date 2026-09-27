package mihon.feature.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Yomikae: translation through a large language model served over the OpenAI-compatible
 * chat API (`POST /v1/chat/completions`). Works with `llama-server` from llama.cpp running on a
 * PC, with Ollama, LM Studio, and with hosted services.
 *
 * Why an LLM: it receives the whole page at once, plus a glossary and background about the
 * series, so names stay consistent and the tone fits comics dialogue. That is what a
 * line-by-line engine like ML Kit cannot do.
 *
 * Prompt shape follows what translation models such as Hy-MT2 are trained on: an optional
 * "reference the following translations" block (the glossary), a "background information"
 * block, then the numbered lines to translate.
 */
class LlmTranslator(
    private val serverUrl: String,
    private val model: String,
    private val targetLanguage: String,
    private val background: String,
    private val glossary: List<Pair<String, String>> = emptyList(),
    /** Lines already translated for this series (names, shouts): reused verbatim. */
    knownLines: Map<String, String> = emptyMap(),
) : TextTranslator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<String, String>(knownLines)

    /** What this run translated, so the job can remember the short lines afterwards. */
    val translated: Map<String, String> get() = cache

    private val endpoint: String
        get() = serverUrl.trimEnd('/') + "/v1/chat/completions"

    override suspend fun prepare() {
        // A tiny request so a wrong address fails fast, before any OCR work is done.
        complete("Reply with the single word OK.")
    }

    override suspend fun translate(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val result = arrayOfNulls<String>(lines.size)
        lines.forEachIndexed { i, line -> cache[line]?.let { result[i] = it } }

        val missing = lines.indices.filter { result[it] == null }
        if (missing.isNotEmpty()) {
            val batch = missing.map { lines[it] }
            val translated = translateBatch(batch)
            missing.forEachIndexed { k, i ->
                val text = translated[k] ?: translateSingle(lines[i])
                cache[lines[i]] = text
                result[i] = text
            }
        }
        return result.map { it.orEmpty() }
    }

    /** Whole page in one prompt; returns null for lines the model did not answer. */
    private suspend fun translateBatch(lines: List<String>): List<String?> {
        val prompt = buildString {
            if (glossary.isNotEmpty()) {
                appendLine("Reference the following translations:")
                glossary.forEach { (from, to) -> appendLine("`$from` translates to `$to`") }
                appendLine()
            }
            appendLine("[Background Information]")
            appendLine(background.ifBlank { DEFAULT_BACKGROUND })
            appendLine()
            appendLine(
                "Please accurately translate the following text into ${languageName(targetLanguage)}, " +
                    "taking the provided background information into consideration. " +
                    "Each numbered line is one speech bubble; translate every line, keep exactly the same " +
                    "number of lines, and start each translated line with the same number and a vertical bar " +
                    "(for example \"3| \"). Only output the translated lines without any additional explanation.",
            )
            appendLine()
            lines.forEachIndexed { i, line -> appendLine("${i + 1}| ${line.replace('\n', ' ')}") }
        }

        val answer = complete(prompt)
        val byNumber = HashMap<Int, String>()
        val inOrder = ArrayList<String>()
        answer.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { raw ->
            val match = LINE_PATTERN.matchEntire(raw)
            if (match != null) {
                val text = match.groupValues[2].trim().trim('`', '"')
                match.groupValues[1].toIntOrNull()?.let { byNumber[it] = text }
                inOrder += text
            } else {
                inOrder += raw.trim('`', '"')
            }
        }
        if (byNumber.size == lines.size) {
            return lines.indices.map { byNumber[it + 1]?.takeIf { text -> text.isNotBlank() } }
        }
        // The model dropped or mangled the numbers (it happens with long reference lists): trust the order.
        logcat(LogPriority.WARN) { "LLM numbered ${byNumber.size} of ${lines.size} lines, using order" }
        return lines.indices.map { inOrder.getOrNull(it)?.takeIf { text -> text.isNotBlank() } }
    }

    private suspend fun translateSingle(line: String): String {
        val prompt = "Translate the following text into ${languageName(targetLanguage)}. " +
            "Note that you should only output the translated result without any additional explanation:\n\n$line"
        return complete(prompt).trim().trim('`', '"')
    }

    private suspend fun complete(prompt: String): String = withIOContext {
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
            put("temperature", 0.7)
            put("top_p", 0.6)
            put("top_k", 20)
            put("repeat_penalty", 1.05)
            put("max_tokens", 2048)
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

    private companion object {
        val LINE_PATTERN = Regex("""^([0-9N]+)\s*[|｜]\s*(.*)$""")
        const val DEFAULT_BACKGROUND =
            "These are the speech bubbles of one page of a Korean webtoon, in reading order. " +
                "Use natural spoken English as in published comics. Keep character names consistent."

        fun languageName(tag: String): String =
            Locale(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { tag }
    }
}
