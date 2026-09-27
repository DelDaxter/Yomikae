package mihon.feature.translation.memory

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.util.system.toast
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Yomikae: the glossary shared by every series ("source = translation" per line, "#" for a
 * comment). Only the entries whose source appears on a page are given to the model.
 */
class GlobalGlossaryScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val preferences = remember { context.appGraph.translationPreferences }
        var text by remember { mutableStateOf(preferences.globalGlossary.get()) }
        val entries = remember(text) { SeriesMemoryStore.parseGlossaryText(text).size }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.global_glossary_title),
                    navigateUp = navigator::pop,
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(MaterialTheme.padding.medium)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(MR.strings.global_glossary_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.padding.small))
                Text(
                    text = stringResource(MR.strings.global_glossary_count, entries),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.padding.small))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 16,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(MaterialTheme.padding.small))
                Row {
                    Button(
                        onClick = {
                            preferences.globalGlossary.set(text)
                            context.toast(MR.strings.series_memory_saved)
                        },
                    ) { Text(stringResource(MR.strings.series_memory_save)) }
                    Spacer(modifier = Modifier.width(MaterialTheme.padding.small))
                    OutlinedButton(
                        onClick = {
                            text = DefaultGlossary.KO_EN
                            preferences.globalGlossary.set(text)
                        },
                    ) { Text(stringResource(MR.strings.global_glossary_reset)) }
                }
            }
        }
    }
}
