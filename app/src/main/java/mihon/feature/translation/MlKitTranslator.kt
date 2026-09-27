package mihon.feature.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Yomikae: Google ML Kit on-device translation. Small (about 30 MB per language), fast, no
 * context: each line is translated on its own, so it stays literal. Pairs without English go
 * through English.
 */
class MlKitTranslator(
    sourceLanguage: String,
    targetLanguage: String,
) : TextTranslator {

    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(requireNotNull(TranslateLanguage.fromLanguageTag(sourceLanguage)))
            .setTargetLanguage(requireNotNull(TranslateLanguage.fromLanguageTag(targetLanguage)))
            .build(),
    )

    private val cache = HashMap<String, String>()

    override suspend fun prepare() {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    override suspend fun translate(lines: List<String>): List<String> = lines.map { line ->
        cache[line] ?: translator.translate(line).await().also { cache[line] = it }
    }

    override fun close() {
        translator.close()
    }
}
