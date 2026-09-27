package mihon.feature.translation.memory

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.feature.translation.ChapterTranslationJob
import mihon.feature.translation.TranslationStore
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Translate
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Yomikae: "Series memory" screen of a manga (from the ⋮ menu of its page).
 *
 * Lets the user pick the human-translated edition of the same work, read its text, match the
 * bubbles of both editions, and edit a glossary. Everything here feeds the LLM prompt.
 */
class SeriesMemoryScreen(private val mangaId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val graph = remember { context.appGraph }
        val memoryStore = graph.seriesMemoryStore
        val scope = rememberCoroutineScope()

        var memory by remember { mutableStateOf(memoryStore.load(mangaId)) }
        var referenceTitle by remember { mutableStateOf<String?>(null) }
        var glossaryText by remember { mutableStateOf(memoryStore.formatGlossary(memory.glossary)) }
        var favorites by remember { mutableStateOf<List<Manga>>(emptyList()) }
        var showPicker by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }

        LaunchedEffect(memory.referenceMangaId) {
            referenceTitle = memory.referenceMangaId?.let { withIOContext { graph.getManga.await(it)?.title } }
        }
        LaunchedEffect(Unit) {
            favorites = withIOContext { graph.getFavorites.await().filter { it.id != mangaId }.sortedBy { it.title } }
        }

        fun reload() {
            memory = memoryStore.load(mangaId)
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.series_memory_title),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            LazyColumn(contentPadding = contentPadding) {
                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.series_memory_reference),
                        subtitle = referenceTitle ?: stringResource(MR.strings.series_memory_reference_none),
                        icon = MaterialSymbols.Rounded.Translate,
                        onPreferenceClick = { showPicker = true },
                    )
                }
                item {
                    Row(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium)) {
                        Button(
                            enabled = memory.referenceMangaId != null && !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val queued =
                                        withIOContext { extractReference(context, graph, memory.referenceMangaId!!) }
                                    busy = false
                                    context.toast(
                                        if (queued >
                                            0
                                        ) {
                                            MR.strings.translation_started
                                        } else {
                                            MR.strings.series_memory_no_reference_text
                                        },
                                    )
                                }
                            },
                        ) { Text(stringResource(MR.strings.series_memory_extract)) }
                        Spacer(modifier = Modifier.width(MaterialTheme.padding.small))
                        OutlinedButton(
                            enabled = memory.referenceMangaId != null && !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val prefs = graph.translationPreferences
                                    val report = withIOContext {
                                        graph.seriesMemoryBuilder.rebuild(
                                            mangaId,
                                            prefs.sourceLanguage.get(),
                                            prefs.targetLanguage.get(),
                                        )
                                    }
                                    busy = false
                                    reload()
                                    if (report.chaptersAligned == 0 && report.chaptersWithoutReferenceText > 0) {
                                        context.toast(MR.strings.series_memory_no_reference_text)
                                    }
                                }
                            },
                        ) { Text(stringResource(MR.strings.series_memory_rebuild)) }
                    }
                    Text(
                        text = stringResource(MR.strings.series_memory_extract_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    )
                }
                item {
                    Text(
                        text = stringResource(
                            MR.strings.series_memory_stats,
                            memory.alignedChapters,
                            memory.examples.size,
                            memory.lines.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(MaterialTheme.padding.medium),
                    )
                    HorizontalDivider()
                }
                item {
                    Column(modifier = Modifier.padding(MaterialTheme.padding.medium)) {
                        Text(
                            text = stringResource(MR.strings.series_memory_glossary),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        OutlinedTextField(
                            value = glossaryText,
                            onValueChange = { glossaryText = it },
                            placeholder = { Text(stringResource(MR.strings.series_memory_glossary_hint)) },
                            minLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(MaterialTheme.padding.small))
                        Row {
                            Button(
                                onClick = {
                                    memoryStore.update(mangaId) {
                                        it.copy(glossary = memoryStore.parseGlossary(glossaryText))
                                    }
                                    reload()
                                    context.toast(MR.strings.series_memory_saved)
                                },
                            ) { Text(stringResource(MR.strings.series_memory_save)) }
                            Spacer(modifier = Modifier.width(MaterialTheme.padding.small))
                            OutlinedButton(
                                enabled = memory.lines.isNotEmpty(),
                                onClick = {
                                    memoryStore.update(mangaId) { it.copy(lines = emptyMap()) }
                                    reload()
                                },
                            ) { Text(stringResource(MR.strings.series_memory_forget_lines)) }
                        }
                    }
                    HorizontalDivider()
                }
                if (memory.examples.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(MR.strings.series_memory_examples),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(MaterialTheme.padding.medium),
                        )
                    }
                    items(memory.examples.take(30)) { pair ->
                        Column(
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 4.dp),
                        ) {
                            Text(
                                pair.source,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                pair.target,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                item {
                    Text(
                        text = stringResource(MR.strings.series_memory_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(MaterialTheme.padding.medium),
                    )
                }
            }
        }

        if (showPicker) {
            var filter by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showPicker = false },
                title = { Text(stringResource(MR.strings.series_memory_pick_reference)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(MaterialTheme.padding.small))
                        LazyColumn(modifier = Modifier.height(320.dp)) {
                            items(
                                favorites.filter {
                                    filter.isBlank() || it.title.contains(filter, ignoreCase = true)
                                },
                            ) { manga ->
                                TextButton(
                                    onClick = {
                                        memoryStore.update(mangaId) {
                                            it.copy(
                                                referenceMangaId = manga.id,
                                                examples = emptyList(),
                                                alignedChapters = 0,
                                            )
                                        }
                                        reload()
                                        showPicker = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(manga.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPicker = false }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }
    }

    /**
     * Queues the extract job for the downloaded chapters of the reference that have no text
     * yet. Returns how many chapters were queued.
     */
    private suspend fun extractReference(
        context: android.content.Context,
        graph: mihon.app.di.AppGraph,
        referenceId: Long,
    ): Int {
        val reference = graph.getManga.await(referenceId) ?: return 0
        val chapters = graph.getChaptersByMangaId.await(referenceId)
        val store: TranslationStore = graph.translationStore
        val variant = ChapterTranslationJob.extractVariant(graph.translationPreferences.targetLanguage.get())
        val todo = chapters.filter { chapter ->
            graph.downloadManager.isChapterDownloaded(
                chapter.name,
                chapter.scanlator,
                chapter.url,
                reference.title,
                reference.source,
            ) &&
                !java.io.File(store.chapterDir(chapter.id, variant), ".done").exists()
        }
        if (todo.isEmpty()) return 0
        ChapterTranslationJob.start(
            context,
            referenceId,
            reference.title,
            todo.map { ChapterTranslationJob.Request(it.id, it.name) },
            mode = ChapterTranslationJob.MODE_EXTRACT,
        )
        return todo.size
    }
}
