package mihon.feature.translation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.toDurationString
import kotlinx.coroutines.delay
import mihon.app.di.appGraph
import mihon.feature.translation.ChapterTranslationJob
import mihon.feature.translation.TranslationQueue
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Cancel
import mihon.icons.materialsymbols.rounded.Done
import mihon.icons.materialsymbols.rounded.Schedule
import mihon.icons.materialsymbols.rounded.Sync
import mihon.icons.materialsymbols.rounded.Translate
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import kotlin.time.Duration.Companion.milliseconds

/**
 * Yomikae: "Translation queue" screen (More → Translation queue, or tap on the progress
 * notification). Shows what is being translated, what waits, and a time-left estimate.
 */
object TranslationQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val queue = remember { context.appGraph.translationQueue }

        val items by queue.items.collectAsState()
        val stats by queue.stats.collectAsState()

        // Tick once a second while something is in progress so the estimate stays fresh.
        val isActive = items.any {
            it.status == TranslationQueue.Status.RUNNING || it.status == TranslationQueue.Status.PENDING
        }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(isActive) {
            while (isActive) {
                delay(1000)
                now = System.currentTimeMillis()
            }
        }
        val remainingMillis = remember(items, stats, now) { queue.estimatedRemainingMillis() }
        val pendingCount = items.count { it.status == TranslationQueue.Status.PENDING }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_translation_queue),
                    subtitle = if (pendingCount > 0) {
                        stringResource(MR.strings.translation_queue_summary, pendingCount)
                    } else {
                        null
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        if (items.isNotEmpty()) {
                            AppBarActions(
                                listOf(
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_cancel_all),
                                        onClick = { ChapterTranslationJob.stop(context) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_clear_finished),
                                        onClick = { queue.clearFinished() },
                                    ),
                                ),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            if (items.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.translation_queue_empty,
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }

            LazyColumn(contentPadding = contentPadding) {
                if (isActive) {
                    item(key = "summary") {
                        SectionCard {
                            Column(modifier = Modifier.padding(MaterialTheme.padding.medium)) {
                                val remainingText = remainingMillis?.let {
                                    it.milliseconds.toDurationString(
                                        context,
                                        fallback = "<1${stringResource(MR.strings.seconds_short).substringAfter("%d")}",
                                    )
                                }
                                Text(
                                    text = if (remainingText != null) {
                                        stringResource(MR.strings.translation_queue_remaining, remainingText)
                                    } else {
                                        stringResource(MR.strings.translation_queue_status_pending)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                stats.averageMillisPerPage?.let { perPage ->
                                    Text(
                                        text = stringResource(MR.strings.translation_queue_per_page, perPage.toInt()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                items(items, key = { it.chapterId }) { item ->
                    QueueRow(item)
                }
            }
        }
    }
}

@Composable
private fun QueueRow(item: TranslationQueue.Item) {
    val (icon, tint) = when (item.status) {
        TranslationQueue.Status.PENDING ->
            MaterialSymbols.Rounded.Schedule to
                MaterialTheme.colorScheme.onSurfaceVariant
        TranslationQueue.Status.RUNNING -> MaterialSymbols.Rounded.Sync to MaterialTheme.colorScheme.primary
        TranslationQueue.Status.DONE -> MaterialSymbols.Rounded.Done to MaterialTheme.colorScheme.primary
        TranslationQueue.Status.ERROR -> MaterialSymbols.Rounded.Cancel to MaterialTheme.colorScheme.error
        TranslationQueue.Status.CANCELLED ->
            MaterialSymbols.Rounded.Cancel to
                MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when (item.status) {
        TranslationQueue.Status.PENDING -> stringResource(MR.strings.translation_queue_status_pending)
        TranslationQueue.Status.RUNNING -> stringResource(
            MR.strings.translation_queue_status_running,
            item.page,
            item.pageCount,
        )
        TranslationQueue.Status.DONE -> stringResource(MR.strings.translation_queue_status_done)
        TranslationQueue.Status.ERROR -> item.error?.let {
            "${stringResource(MR.strings.translation_queue_status_error)} • $it"
        } ?: stringResource(MR.strings.translation_queue_status_error)
        TranslationQueue.Status.CANCELLED -> stringResource(MR.strings.translation_queue_status_cancelled)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusIcon(icon = icon, tint = tint)
        Spacer(modifier = Modifier.width(MaterialTheme.padding.medium))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = item.mangaTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.chapterName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = tint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.status == TranslationQueue.Status.RUNNING && item.pageCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { item.page.toFloat() / item.pageCount },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, tint: androidx.compose.ui.graphics.Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(24.dp),
    )
}

@Suppress("unused")
private val translateIcon = MaterialSymbols.Rounded.Translate
