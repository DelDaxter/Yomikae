package mihon.feature.merge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale

/**
 * Yomikae: the reading language of one series. Opened from the button in the series page's
 * action row ("EN"). The chosen language drives which edition's title and synopsis are shown
 * and which version of each chapter comes first in a unified entry.
 */
@Composable
fun ReadingLanguageDialog(
    current: String,
    options: List<String>,
    onDismissRequest: () -> Unit,
    onSelected: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.reading_language_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(MR.strings.reading_language_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                options.forEach { language ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(language) }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = language == current, onClick = { onSelected(language) })
                        Text(languageLabel(language), modifier = Modifier.padding(start = 8.dp))
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

/** "EN - English": the code, then the name in the phone's language. */
fun languageLabel(language: String): String {
    val name = LocaleHelper.getDisplayName(language).replaceFirstChar { it.titlecase(Locale.getDefault()) }
    return "${language.uppercase(Locale.ROOT)} - $name"
}
