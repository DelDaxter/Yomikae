package mihon.feature.merge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** One library entry offered for merging. */
class MergeCandidate(val mangaId: Long, val title: String, val sourceLabel: String)

/**
 * Yomikae: picks the library entries that are the same work as the current one. Checked
 * entries become members of the unified entry; unchecking removes them.
 */
@Composable
fun MergeDialog(
    candidates: List<MergeCandidate>,
    initialMembers: Set<Long>,
    onDismissRequest: () -> Unit,
    onConfirm: (members: List<Long>) -> Unit,
    /** Dissolves the group entirely; null when there is no group yet. */
    onDissolve: (() -> Unit)? = null,
) {
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initialMembers) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.merge_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(MR.strings.merge_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(MR.strings.action_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    items(
                        candidates.filter { filter.isBlank() || it.title.contains(filter, ignoreCase = true) },
                        key = { it.mangaId },
                    ) { candidate ->
                        val checked = candidate.mangaId in selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected =
                                        if (checked) selected - candidate.mangaId else selected + candidate.mangaId
                                }
                                .padding(vertical = 2.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(candidate.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    candidate.sourceLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.toList()) }) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            Row {
                if (onDissolve != null) {
                    TextButton(onClick = onDissolve) {
                        Text(stringResource(MR.strings.merge_dissolve))
                    }
                }
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            }
        },
    )
}
