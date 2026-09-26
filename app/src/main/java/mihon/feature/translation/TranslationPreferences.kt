package mihon.feature.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
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

    companion object {
        val SOURCE_LANGUAGES = listOf("ko", "ja", "en")
        val TARGET_LANGUAGES = listOf("en", "fr")
    }
}
