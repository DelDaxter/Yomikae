package mihon.feature.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import mihon.feature.translation.ocr.OcrEngine
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Yomikae: user settings for page translation.
 *
 * Language codes are BCP-47 tags understood by ML Kit ("ko", "ja", "en", "fr").
 */
@Inject
@SingleIn(AppScope::class)
class TranslationPreferences(
    preferenceStore: PreferenceStore,
) {

    /** Language of the text printed on the pages. */
    val sourceLanguage: Preference<String> = preferenceStore.getString("translation_source_language", "ko")

    /** Language the pages are translated into. */
    val targetLanguage: Preference<String> = preferenceStore.getString("translation_target_language", "en")

    /** When true, the reader shows the translated page whenever one exists. */
    val showTranslated: Preference<Boolean> = preferenceStore.getBoolean("translation_show_translated", true)

    /** Which [TextTranslator] does the translating: [TextTranslator.ENGINE_MLKIT] or [TextTranslator.ENGINE_LLM]. */
    val engine: Preference<String> = preferenceStore.getString("translation_engine", TextTranslator.ENGINE_MLKIT)

    /** Which [mihon.feature.translation.ocr.OcrEngine] reads the pages: "mlkit" or "paddle". */
    val ocrEngine: Preference<String> = preferenceStore.getString("translation_ocr_engine", OcrEngine.ENGINE_PADDLE)

    /** OpenAI-compatible server for the LLM engine (llama-server on a PC, Ollama, ...). */
    val llmServerUrl: Preference<String> = preferenceStore.getString("translation_llm_url", "")

    /** Model name sent to the server; llama-server ignores it, hosted services need it. */
    val llmModel: Preference<String> = preferenceStore.getString("translation_llm_model", "")

    /** Free text given to the LLM as background: series, tone, names. */
    val llmBackground: Preference<String> = preferenceStore.getString("translation_llm_background", "")

    /** Run the embedded model on the GPU (faster, falls back to CPU when the driver refuses). */
    val localLlmGpu: Preference<Boolean> = preferenceStore.getBoolean("translation_local_llm_gpu", true)

    /** While reading, the current and the next downloaded chapters are translated in the background. */
    val prefetchWhileReading: Preference<Boolean> = preferenceStore.getBoolean("translation_prefetch", true)

    /** Global default: every finished download is queued for translation. */
    val autoTranslateDownloads: Preference<Boolean> = preferenceStore.getBoolean("translation_auto_downloads", false)

    /** Series that always / never translate their downloads, whatever the global default. */
    private val autoTranslateOn: Preference<Set<String>> = preferenceStore.getStringSet("translation_auto_on")
    private val autoTranslateOff: Preference<Set<String>> = preferenceStore.getStringSet("translation_auto_off")

    /** Per-series choice for "translate new downloads". */
    enum class AutoMode { DEFAULT, ON, OFF }

    fun autoTranslateMode(mangaId: Long): AutoMode {
        val key = mangaId.toString()
        return when {
            key in autoTranslateOn.get() -> AutoMode.ON
            key in autoTranslateOff.get() -> AutoMode.OFF
            else -> AutoMode.DEFAULT
        }
    }

    fun setAutoTranslateMode(mangaId: Long, mode: AutoMode) {
        val key = mangaId.toString()
        autoTranslateOn.set(if (mode == AutoMode.ON) autoTranslateOn.get() + key else autoTranslateOn.get() - key)
        autoTranslateOff.set(if (mode == AutoMode.OFF) autoTranslateOff.get() + key else autoTranslateOff.get() - key)
    }

    /** Should a chapter of this series be translated as soon as it is downloaded? */
    fun autoTranslateFor(mangaId: Long): Boolean = when (autoTranslateMode(mangaId)) {
        AutoMode.ON -> true
        AutoMode.OFF -> false
        AutoMode.DEFAULT -> autoTranslateDownloads.get()
    }

    companion object {
        val SOURCE_LANGUAGES = listOf("ko", "ja", "en")
        val TARGET_LANGUAGES = listOf("en", "fr")
        val ENGINES = listOf(TextTranslator.ENGINE_LOCAL, TextTranslator.ENGINE_MLKIT, TextTranslator.ENGINE_LLM)
    }
}
