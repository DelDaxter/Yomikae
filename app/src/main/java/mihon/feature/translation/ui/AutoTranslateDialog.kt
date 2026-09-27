package mihon.feature.translation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mihon.feature.translation.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Yomikae: per-series choice for "translate new downloads": follow the global setting, always,
 * or never. Opened from the series page's overflow menu.
 */
@Composable
fun AutoTranslateDialog(
    current: TranslationPreferences.AutoMode,
    globalEnabled: Boolean,
    onDismissRequest: () -> Unit,
    onSelected: (TranslationPreferences.AutoMode) -> Unit,
) {
    val globalLabel = stringResource(
        if (globalEnabled) MR.strings.auto_translate_state_on else MR.strings.auto_translate_state_off,
    )
    val options = listOf(
        TranslationPreferences.AutoMode.DEFAULT to stringResource(MR.strings.auto_translate_default, globalLabel),
        TranslationPreferences.AutoMode.ON to stringResource(MR.strings.auto_translate_on),
        TranslationPreferences.AutoMode.OFF to stringResource(MR.strings.auto_translate_off),
    )
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.auto_translate_title)) },
        text = {
            Column {
                options.forEach { (mode, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(mode) }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = mode == current, onClick = { onSelected(mode) })
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
