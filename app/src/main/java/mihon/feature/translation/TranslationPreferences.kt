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
    val ocrEngine: Preference<String> = preferenceStore.getString("translation_ocr_engine", OcrEngine.ENGINE_MLKIT)

    /** OpenAI-compatible server for the LLM engine (llama-server on a PC, Ollama, ...). */
    val llmServerUrl: Preference<String> = preferenceStore.getString("translation_llm_url", "http://192.168.31.77:8080")

    /** Model name sent to the server; llama-server ignores it, hosted services need it. */
    val llmModel: Preference<String> = preferenceStore.getString("translation_llm_model", "")

    /** Free text given to the LLM as background: series, tone, names. */
    val llmBackground: Preference<String> = preferenceStore.getString("translation_llm_background", "")

    companion object {
        val SOURCE_LANGUAGES = listOf("ko", "ja", "en")
        val TARGET_LANGUAGES = listOf("en", "fr")
        val ENGINES = listOf(TextTranslator.ENGINE_MLKIT, TextTranslator.ENGINE_LLM)
    }
}
