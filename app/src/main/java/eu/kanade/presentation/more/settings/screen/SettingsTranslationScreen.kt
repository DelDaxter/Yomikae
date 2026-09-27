package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.feature.translation.LocalLlmBackend
import mihon.feature.translation.TextTranslator
import mihon.feature.translation.TranslationPreferences
import mihon.feature.translation.ocr.ModelDownloader
import mihon.feature.translation.ocr.OcrEngine
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.util.Locale

/**
 * Yomikae: Settings → Translation. Languages, which engine reads the pages, which one
 * translates them, and the embedded model's download.
 */
object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val preferences = remember { context.appGraph.translationPreferences }
        val languageName: (String) -> String = { code -> Locale(code).getDisplayLanguage(Locale.getDefault()) }
        val engine by preferences.engine.collectAsState()

        return listOf(
            Preference.PreferenceItem.ListPreference(
                preference = preferences.sourceLanguage,
                entries = TranslationPreferences.SOURCE_LANGUAGES.associateWith(languageName),
                title = stringResource(MR.strings.pref_translation_source_language),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.targetLanguage,
                entries = TranslationPreferences.TARGET_LANGUAGES.associateWith(languageName),
                title = stringResource(MR.strings.pref_translation_target_language),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.showTranslated,
                title = stringResource(MR.strings.pref_translation_show),
                subtitle = stringResource(MR.strings.pref_translation_show_summary),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_group_engines),
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.ocrEngine,
                        entries = mapOf(
                            OcrEngine.ENGINE_MLKIT to stringResource(MR.strings.translation_ocr_mlkit),
                            OcrEngine.ENGINE_PADDLE to stringResource(MR.strings.translation_ocr_paddle),
                        ),
                        title = stringResource(MR.strings.pref_translation_ocr_engine),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.engine,
                        entries = mapOf(
                            TextTranslator.ENGINE_LOCAL to stringResource(MR.strings.translation_engine_local),
                            TextTranslator.ENGINE_MLKIT to stringResource(MR.strings.translation_engine_mlkit),
                            TextTranslator.ENGINE_LLM to stringResource(MR.strings.translation_engine_llm),
                        ),
                        title = stringResource(MR.strings.pref_translation_engine),
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = preferences.llmBackground,
                        title = stringResource(MR.strings.pref_translation_llm_background),
                        enabled = engine != TextTranslator.ENGINE_MLKIT,
                    ),
                ),
            ),
            getLocalModelGroup(preferences),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_group_server),
                enabled = engine == TextTranslator.ENGINE_LLM,
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = preferences.llmServerUrl,
                        title = stringResource(MR.strings.pref_translation_llm_url),
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = preferences.llmModel,
                        title = stringResource(MR.strings.pref_translation_llm_model),
                    ),
                ),
            ),
            Preference.PreferenceItem.InfoPreference(
                title = stringResource(MR.strings.pref_translation_info),
            ),
        )
    }

    /** The embedded model: status, download with progress, deletion. */
    @Composable
    private fun getLocalModelGroup(preferences: TranslationPreferences): Preference.PreferenceGroup {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val downloader = remember { ModelDownloader(context) }
        val specs = remember { listOf(LocalLlmBackend.MODEL) }
        var status by remember { mutableStateOf(ModelStatus.read(downloader, specs)) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        val subtitle = when {
            error != null -> stringResource(MR.strings.translation_model_error, error.orEmpty())
            busy -> stringResource(MR.strings.translation_model_downloading, status.percent)
            status.ready -> stringResource(MR.strings.translation_model_ready, status.totalMb)
            status.percent > 0 -> stringResource(MR.strings.translation_model_partial, status.percent, status.totalMb)
            else -> stringResource(MR.strings.translation_model_missing, status.totalMb)
        }

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_translation_group_local),
            preferenceItems = listOf(
                // Always visible (Mihon hides disabled items): the row shows the model's state.
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.translation_model_name),
                    subtitle = subtitle,
                    onClick = click@{
                        if (busy || status.ready) return@click
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                withIOContext {
                                    downloader.ensure(LocalLlmBackend.GROUP, specs) { _, done, total ->
                                        status = ModelStatus(false, done, total)
                                    }
                                }
                            } catch (e: Exception) {
                                error = e.message ?: e.javaClass.simpleName
                            } finally {
                                status = ModelStatus.read(downloader, specs)
                                busy = false
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = preferences.localLlmGpu,
                    title = stringResource(MR.strings.pref_translation_local_gpu),
                    subtitle = stringResource(MR.strings.pref_translation_local_gpu_summary),
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.translation_model_delete),
                    enabled = !busy && status.percent > 0,
                    onClick = {
                        // Not while a job may be using it: the engine keeps the file mapped.
                        downloader.delete(LocalLlmBackend.GROUP, specs)
                        status = ModelStatus.read(downloader, specs)
                    },
                ),
            ),
        )
    }

    private class ModelStatus(val ready: Boolean, val done: Long, val total: Long) {
        val percent: Int get() = if (total == 0L) 0 else (done * 100 / total).toInt()
        val totalMb: Int get() = (total / 1_000_000).toInt()

        companion object {
            fun read(downloader: ModelDownloader, specs: List<ModelDownloader.Spec>): ModelStatus {
                val total = specs.sumOf { it.sizeBytes }
                val ready = downloader.isReady(LocalLlmBackend.GROUP, specs)
                val done = if (ready) total else downloader.bytesPresent(LocalLlmBackend.GROUP, specs)
                return ModelStatus(ready, done, total)
            }
        }
    }
}
