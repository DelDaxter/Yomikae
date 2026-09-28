package mihon.feature.translation

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import eu.kanade.tachiyomi.util.system.connectivityManager
import logcat.LogPriority
import mihon.feature.translation.ocr.ModelDownloader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/**
 * Yomikae: the translation model running inside the app, through Google's LiteRT-LM runtime
 * (Apache-2.0). This is the real target of the project: OCR and translation both stay on the
 * phone, nothing leaves the device.
 *
 * Model: Hy-MT2 1.8B (Tencent, Apache-2.0), a translation-tuned model for 33 languages,
 * converted to `.litertlm` by the LiteRT community (int8, 1.8 GB). Downloaded once into
 * `filesDir/models/llm/`, never shipped in the APK. On a Galaxy S26 the GPU decodes about
 * 20 tokens per second, the CPU about 12; one page of dialogue takes a few seconds.
 *
 * Why LiteRT-LM rather than llama.cpp: a plain Maven dependency (no NDK, no native build),
 * GPU and NPU backends maintained by Google, and the community already publishes the exact
 * model we want. llama.cpp stays an option for a later plugin (GGUF files).
 */
class LocalLlmBackend(
    private val context: Context,
    private val useGpu: Boolean,
    private val variant: String = VARIANT_INT8,
    private val onDownloadProgress: ModelDownloader.Progress? = null,
) : LlmBackend {

    private val model: ModelDownloader.Spec get() = spec(variant)

    /** Measured on a Galaxy S26: 60 pairs cost about 4 s of prefill per page; 12 cost under 1 s. */
    override val maxReferencePairs: Int = 12

    private val downloader = ModelDownloader(context)
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    override suspend fun prepare() {
        // The model is downloaded once, and never silently on a metered connection.
        if (!downloader.isReady(GROUP, listOf(model)) && context.connectivityManager.isActiveNetworkMetered) {
            error(context.stringResource(MR.strings.translation_model_needs_wifi))
        }
        downloader.ensure(GROUP, listOf(model), onDownloadProgress)
        val path = downloader.file(GROUP, model).path
        withIOContext {
            engine = openEngine(path)
        }
    }

    /** GPU first when asked, CPU as fallback: the GPU delegate can refuse a device or a driver. */
    @OptIn(ExperimentalApi::class)
    private fun openEngine(path: String): Engine {
        // Per-page prefill/decode statistics (getBenchmarkInfo) are only collected with this flag.
        ExperimentalFlags.enableBenchmark = true
        // Hy-MT2's own template ends a finished conversation with an end-of-text marker that
        // the runtime then cannot extend with a new turn ("new rendered template string does
        // not start with the previous"): the same template without that marker, so a
        // conversation can last a whole chapter.
        ExperimentalFlags.overwritePromptTemplate = HY_MT2_TEMPLATE
        val backends = if (useGpu) listOf(Backend.GPU(), Backend.CPU()) else listOf(Backend.CPU())
        var failure: Throwable? = null
        for (backend in backends) {
            val config = EngineConfig(
                modelPath = path,
                backend = backend,
                maxNumTokens = CONTEXT_TOKENS,
                // The runtime keeps compiled artifacts here, which makes the second load faster.
                cacheDir = context.cacheDir.path,
            )
            val candidate = Engine(config)
            try {
                candidate.initialize()
                logcat { "Local LLM ready on ${backend.name}" }
                return candidate
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Local LLM failed on ${backend.name}" }
                runCatching { candidate.close() }
                failure = e
            }
        }
        throw IllegalStateException("Cannot load the local translation model", failure)
    }

    private fun conversationConfig() = ConversationConfig(
        samplerConfig = SamplerConfig(
            topK = LlmTranslator.TOP_K,
            topP = LlmTranslator.TOP_P,
            temperature = LlmTranslator.TEMPERATURE,
        ),
        maxOutputToken = LlmTranslator.MAX_OUTPUT_TOKENS,
    )

    /** A conversation of its own: nothing remembered, nothing to disturb the session one. */
    override suspend fun complete(prompt: String): String = withIOContext {
        val engine = engine ?: error("Local LLM not prepared")
        engine.createConversation(conversationConfig()).use { conversation ->
            val started = System.nanoTime()
            val answer = conversation.sendMessage(prompt).toString()
            logTiming(conversation, started)
            answer
        }
    }

    override suspend fun chat(turn: String, reset: Boolean): String = withIOContext {
        val engine = engine ?: error("Local LLM not prepared")
        val current = conversation?.takeIf { !reset && it.isAlive } ?: run {
            runCatching { conversation?.close() }
            engine.createConversation(conversationConfig()).also { conversation = it }
        }
        val started = System.nanoTime()
        // Trimmed: the runtime re-renders the whole history with the chat template at every turn
        // and requires the new rendering to extend the previous one byte for byte.
        val answer = try {
            current.sendMessage(turn.trim()).toString()
        } catch (e: Exception) {
            if (reset) throw e
            runCatching { current.close() }
            conversation = null
            throw LlmBackend.ConversationLostException(e)
        }
        logTiming(current, started)
        answer
    }

    override fun conversationTokens(): Int = runCatching { conversation?.getTokenCount() ?: 0 }.getOrDefault(0)

    /**
     * Where the time goes (prefill = reading the prompt, decode = writing the answer): the
     * numbers that decide whether a shorter prompt or a faster model helps more. Measured on a
     * Galaxy S26: ~800 prompt tokens at 220 tok/s against 12-40 answer tokens, so prefill wins.
     */
    @OptIn(ExperimentalApi::class)
    private fun logTiming(conversation: Conversation, started: Long) {
        val elapsed = (System.nanoTime() - started) / 1e9
        val b: BenchmarkInfo = runCatching<BenchmarkInfo> { conversation.getBenchmarkInfo() }.getOrNull() ?: return
        logcat {
            (
                "Local LLM turn: %.2fs total, prefill %d tok @ %.0f tok/s (ttft %.2fs), " +
                    "decode %d tok @ %.1f tok/s, context %d tok"
                ).format(
                elapsed,
                b.lastPrefillTokenCount,
                b.lastPrefillTokensPerSecond,
                b.timeToFirstTokenInSecond,
                b.lastDecodeTokenCount,
                b.lastDecodeTokensPerSecond,
                conversationTokens(),
            )
        }
    }

    override fun close() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
    }

    companion object {
        const val GROUP = "llm"

        /** tencent/Hy-MT2-1.8B `chat_template.jinja`, minus the trailing `<|hy_place_holder_no_8|>`. */
        private const val HY_MT2_TEMPLATE =
            "{% if messages[0]['role'] == 'system' %}{% set loop_messages = messages[1:] %}" +
                "{% set system_message = messages[0]['content'] %}<｜hy_begin▁of▁sentence｜>" +
                "{{ system_message }}<｜hy_place▁holder▁no▁3｜>{% else %}" +
                "{% set loop_messages = messages %}<｜hy_begin▁of▁sentence｜>{% endif %}" +
                "{% for message in loop_messages %}{% if message['role'] == 'user' %}<｜hy_User｜>" +
                "{{ message['content'] }}{% elif message['role'] == 'assistant' %}<｜hy_Assistant｜>" +
                "{{ message['content'] }}<｜hy_place▁holder▁no▁2｜>{% endif %}{% endfor %}" +
                "{% if add_generation_prompt %}<｜hy_Assistant｜>{% endif %}"

        /** Whole prompt + answer budget; a page of ten bubbles with references fits in 1500. */
        private const val CONTEXT_TOKENS = 4096

        /** int8 (reference quality, works well on CPU too) or int4 (smaller, faster decoding on the GPU). */
        const val VARIANT_INT8 = "int8"
        const val VARIANT_INT4 = "int4"
        val VARIANTS = listOf(VARIANT_INT8, VARIANT_INT4)

        /** litert-community/Hy-MT2-1.8B, int8, checksum from its `litertlm_manifest.json`. */
        val MODEL_INT8 = ModelDownloader.Spec(
            fileName = "Hy-MT2-1.8B_int8.litertlm",
            url = "https://huggingface.co/litert-community/Hy-MT2-1.8B/resolve/main/Hy-MT2-1.8B_int8.litertlm",
            sha256 = "529e6d378df5869d89a5a08717c06604105a32d8a4dab4800175d6baabc4da50",
            sizeBytes = 1_815_622_960L,
        )

        /**
         * Same model in int4 (blockwise-32 OCTAV, int8 embedding), converted by Yomikae with
         * hf-to-litertlm / litert-torch from tencent/Hy-MT2-1.8B and published with the app.
         */
        val MODEL_INT4 = ModelDownloader.Spec(
            fileName = "Hy-MT2-1.8B_int4_block32.litertlm",
            url = "https://github.com/DelDaxter/Yomikae/releases/download/models-v1/Hy-MT2-1.8B_int4_block32.litertlm",
            sha256 = "b945906678c2b13826f0481c0eca5b9f657f38393fa48bb4abc98f23d68416cc",
            sizeBytes = 1_512_649_232L,
        )

        fun spec(variant: String): ModelDownloader.Spec = if (variant == VARIANT_INT4) MODEL_INT4 else MODEL_INT8

        fun isModelReady(context: Context, variant: String): Boolean =
            ModelDownloader(context).isReady(GROUP, listOf(spec(variant)))
    }
}
