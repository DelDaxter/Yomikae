package mihon.feature.translation

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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
    private val onDownloadProgress: ModelDownloader.Progress? = null,
) : LlmBackend {

    /** Measured on a Galaxy S26: 60 pairs cost about 4 s of prefill per page; 12 cost under 1 s. */
    override val maxReferencePairs: Int = 12

    private val downloader = ModelDownloader(context)
    private var engine: Engine? = null

    override suspend fun prepare() {
        // The model is downloaded once, and never silently on a metered connection.
        if (!downloader.isReady(GROUP, listOf(MODEL)) && context.connectivityManager.isActiveNetworkMetered) {
            error(context.stringResource(MR.strings.translation_model_needs_wifi))
        }
        downloader.ensure(GROUP, listOf(MODEL), onDownloadProgress)
        val path = downloader.file(GROUP, MODEL).path
        withIOContext {
            engine = openEngine(path)
        }
    }

    /** GPU first when asked, CPU as fallback: the GPU delegate can refuse a device or a driver. */
    private fun openEngine(path: String): Engine {
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

    override suspend fun complete(prompt: String): String = withIOContext {
        val engine = engine ?: error("Local LLM not prepared")
        // One conversation per page: the model must not remember the previous page's answer,
        // and a fresh context keeps the prompt short and the speed constant.
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = LlmTranslator.TOP_K,
                topP = LlmTranslator.TOP_P,
                temperature = LlmTranslator.TEMPERATURE,
            ),
            maxOutputToken = LlmTranslator.MAX_OUTPUT_TOKENS,
        )
        engine.createConversation(config).use { conversation ->
            conversation.sendMessage(prompt).toString()
        }
    }

    override fun close() {
        runCatching { engine?.close() }
        engine = null
    }

    companion object {
        const val GROUP = "llm"

        /** Whole prompt + answer budget; a page of ten bubbles with references fits in 1500. */
        private const val CONTEXT_TOKENS = 4096

        /** litert-community/Hy-MT2-1.8B, int8, checksum from its `litertlm_manifest.json`. */
        val MODEL = ModelDownloader.Spec(
            fileName = "Hy-MT2-1.8B_int8.litertlm",
            url = "https://huggingface.co/litert-community/Hy-MT2-1.8B/resolve/main/Hy-MT2-1.8B_int8.litertlm",
            sha256 = "529e6d378df5869d89a5a08717c06604105a32d8a4dab4800175d6baabc4da50",
            sizeBytes = 1_815_622_960L,
        )

        fun isModelReady(context: Context): Boolean = ModelDownloader(context).isReady(GROUP, listOf(MODEL))
    }
}
