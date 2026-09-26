package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import mihon.feature.translation.TranslationState
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Translate
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Yomikae: what the user can ask from the translation button of a chapter row. */
enum class ChapterTranslationAction {
    TRANSLATE,
    RETRANSLATE,
    DELETE,
}

/**
 * Yomikae: the translation button of a chapter row, next to the download indicator.
 *
 * Grey: not translated (tap to translate). Ring: queued or translating, with progress.
 * Coloured: translated (tap for "translate again" / "delete translation"). Red: failed (tap to retry).
 */
@Composable
fun ChapterTranslationIndicator(
    enabled: Boolean,
    translationStateProvider: () -> TranslationState,
    translationProgressProvider: () -> Float,
    onClick: (ChapterTranslationAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = translationStateProvider()
    var isMenuExpanded by remember { mutableStateOf(false) }

    val clickAction: () -> Unit = when (state) {
        TranslationState.NONE, TranslationState.ERROR -> {
            { onClick(ChapterTranslationAction.TRANSLATE) }
        }
        TranslationState.DONE -> {
            { isMenuExpanded = true }
        }
        TranslationState.QUEUED, TranslationState.RUNNING -> {
            {}
        }
    }

    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = clickAction),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            TranslationState.QUEUED -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(IndicatorSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = IndicatorStrokeWidth,
                )
                Icon(
                    imageVector = MaterialSymbols.Rounded.Translate,
                    contentDescription = stringResource(MR.strings.translation_chapter_queued),
                    modifier = Modifier.size(IconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TranslationState.RUNNING -> {
                CircularProgressIndicator(
                    progress = translationProgressProvider,
                    modifier = Modifier.size(IndicatorSize),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = IndicatorStrokeWidth,
                )
                Icon(
                    imageVector = MaterialSymbols.Rounded.Translate,
                    contentDescription = stringResource(MR.strings.translation_notifier_title),
                    modifier = Modifier.size(IconSize),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            TranslationState.DONE -> {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Translate,
                    contentDescription = stringResource(MR.strings.translation_chapter_translated),
                    modifier = Modifier.size(IndicatorSize),
                    tint = MaterialTheme.colorScheme.primary,
                )
                DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(MR.strings.action_retranslate)) },
                        onClick = {
                            onClick(ChapterTranslationAction.RETRANSLATE)
                            isMenuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(MR.strings.action_delete_translation)) },
                        onClick = {
                            onClick(ChapterTranslationAction.DELETE)
                            isMenuExpanded = false
                        },
                    )
                }
            }
            TranslationState.ERROR -> {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Translate,
                    contentDescription = stringResource(MR.strings.translation_queue_status_error),
                    modifier = Modifier.size(IndicatorSize),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            TranslationState.NONE -> {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Translate,
                    contentDescription = stringResource(MR.strings.action_translate),
                    modifier = Modifier.size(IndicatorSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                )
            }
        }
    }
}

private val IndicatorSize = 26.dp
private val IconSize = 14.dp
private val IndicatorStrokeWidth = 2.dp
