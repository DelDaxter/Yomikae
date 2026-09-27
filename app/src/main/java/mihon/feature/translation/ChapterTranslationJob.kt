package mihon.feature.translation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.asFlow
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.app.di.appGraph
import mihon.core.metro.metroGraph
import mihon.feature.translation.memory.SeriesMemory
import mihon.feature.translation.memory.SeriesMemoryBuilder
import mihon.feature.translation.memory.SeriesMemoryStore
import mihon.feature.translation.ocr.MlKitOcr
import mihon.feature.translation.ocr.OcrEngine
import mihon.feature.translation.ocr.PaddleOcr
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Yomikae: background job that translates downloaded chapters, one page after the other, and
 * stores the result through [TranslationStore].
 *
 * It follows the same pattern as Mihon's [eu.kanade.tachiyomi.data.download.DownloadJob]: a
 * WorkManager worker with a foreground notification, so the system keeps it alive while the
 * user does something else. Chapters asked for while a job runs are appended to the same queue.
 */
class ChapterTranslationJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject private lateinit var getManga: GetManga

    @Inject private lateinit var getChapter: GetChapter

    @Inject private lateinit var sourceManager: SourceManager

    @Inject private lateinit var downloadManager: DownloadManager

    @Inject private lateinit var downloadProvider: DownloadProvider

    @Inject private lateinit var store: TranslationStore

    @Inject private lateinit var preferences: TranslationPreferences

    @Inject private lateinit var queue: TranslationQueue

    @Inject private lateinit var memoryStore: SeriesMemoryStore

    @Inject private lateinit var memoryBuilder: SeriesMemoryBuilder

    init {
        graph.inject(this)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(context.stringResource(MR.strings.translation_notifier_title))
            setSmallIcon(R.drawable.ic_translate_24dp)
            setContentIntent(openQueuePendingIntent(context))
            setOngoing(true)
            setOnlyAlertOnce(true)
        }.build()
        return ForegroundInfo(
            ID_TRANSLATION_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        val mangaId = inputData.getLong(KEY_MANGA_ID, -1L)
        val chapterIds = inputData.getLongArray(KEY_CHAPTER_IDS) ?: return Result.failure()
        val manga = getManga.await(mangaId) ?: return Result.failure()
        val source = sourceManager.get(manga.source) ?: return Result.failure()

        setForegroundSafely()

        val mode = inputData.getString(KEY_MODE) ?: MODE_TRANSLATE
        val sourceLanguage = preferences.sourceLanguage.get()
        val targetLanguage = preferences.targetLanguage.get()
        // Extract mode reads a human-translated edition: its pages are in the target language.
        val extractOnly = mode == MODE_EXTRACT || sourceLanguage == targetLanguage
        val ocrLanguage = if (mode == MODE_EXTRACT) targetLanguage else sourceLanguage
        val variant = if (mode == MODE_EXTRACT) extractVariant(targetLanguage) else store.currentVariant()

        // Series memory: human examples, user glossary and lines already translated.
        val memory = memoryStore.load(mangaId)
        if (!extractOnly && memory.referenceMangaId != null) {
            runCatching { memoryBuilder.rebuild(mangaId, sourceLanguage, targetLanguage) }
                .onFailure { logcat(LogPriority.WARN, it) { "Series memory rebuild failed" } }
        }
        val freshMemory = memoryStore.load(mangaId)
        val textTranslator = when {
            extractOnly -> IdentityTranslator()
            else -> createTextTranslator(sourceLanguage, targetLanguage, freshMemory)
        }
        val translator = PageTranslator(createOcrEngine(ocrLanguage), textTranslator, renderPages = !extractOnly)
        var failures = 0
        try {
            translator.prepare()

            val chapters = chapterIds.toList().mapNotNull { getChapter.await(it) }
            // After a process restart the in-memory queue is empty: register what we work on.
            queue.enqueue(
                chapters
                    .filter { queue.statusOf(it.id) == null }
                    .map { TranslationQueue.Item(it.id, manga.id, manga.title, it.name) },
            )

            for (chapter in chapters) {
                if (isStopped) break
                try {
                    translateChapter(translator, manga, source, chapter, variant)
                    if (textTranslator is LlmTranslator) rememberShortLines(mangaId, textTranslator.translated)
                } catch (e: Exception) {
                    failures++
                    queue.markError(chapter.id, e.message)
                    logcat(LogPriority.ERROR, e) { "Translation failed for ${chapter.name}" }
                    context.notify(ID_TRANSLATION_ERROR, Notifications.CHANNEL_DOWNLOADER_ERROR) {
                        setContentTitle(context.stringResource(MR.strings.translation_notifier_error, chapter.name))
                        setContentText(e.message)
                        setSmallIcon(R.drawable.ic_translate_24dp)
                    }
                }
            }
        } catch (e: Exception) {
            chapterIds.forEach { queue.markError(it, e.message) }
            logcat(LogPriority.ERROR, e) { "Translation job failed" }
            context.notify(ID_TRANSLATION_ERROR, Notifications.CHANNEL_DOWNLOADER_ERROR) {
                setContentTitle(context.stringResource(MR.strings.translation_notifier_error, manga.title))
                setContentText(e.message)
                setSmallIcon(R.drawable.ic_translate_24dp)
            }
            return Result.failure()
        } finally {
            translator.close()
            context.cancelNotification(ID_TRANSLATION_PROGRESS)
            if (isStopped) queue.cancelAll()
        }

        return if (failures == 0) Result.success() else Result.failure()
    }

    /** Picks the OCR engine from the settings; PaddleOCR only for the languages it covers. */
    private fun createOcrEngine(language: String): OcrEngine {
        val wanted = preferences.ocrEngine.get()
        return if (wanted == OcrEngine.ENGINE_PADDLE && language in PaddleOcr.SUPPORTED_LANGUAGES) {
            PaddleOcr(context, language)
        } else {
            MlKitOcr(language)
        }
    }

    /** Picks the translation engine from the settings, fed with the series memory. */
    private fun createTextTranslator(
        sourceLanguage: String,
        targetLanguage: String,
        memory: SeriesMemory,
    ): TextTranslator {
        return when (preferences.engine.get()) {
            TextTranslator.ENGINE_LLM -> LlmTranslator(
                serverUrl = preferences.llmServerUrl.get(),
                model = preferences.llmModel.get(),
                targetLanguage = targetLanguage,
                background = preferences.llmBackground.get(),
                memory = memory,
                knownLines = memory.lines,
            )
            else -> MlKitTranslator(sourceLanguage, targetLanguage)
        }
    }

    /** Keeps how short lines (names, shouts) were rendered, so later chapters reuse them. */
    private fun rememberShortLines(mangaId: Long, translated: Map<String, String>) {
        val short = translated.filter { (k, v) ->
            k.length <= SeriesMemory.MAX_LINE_LENGTH && v.isNotBlank() && k.any { it.isLetter() }
        }
        if (short.isEmpty()) return
        memoryStore.update(mangaId) { it.copy(lines = it.lines + short) }
    }

    private suspend fun translateChapter(
        translator: PageTranslator,
        manga: tachiyomi.domain.manga.model.Manga,
        source: eu.kanade.tachiyomi.source.Source,
        chapter: Chapter,
        variant: String,
    ) {
        // Reuse the reader's own loader so page order is exactly what the reader will show.
        // The loader must stay open until the last page is read: for CBZ chapters the page
        // streams come from a native archive reader that recycle() closes.
        val readerChapter = ReaderChapter(chapter)
        val loader = DownloadPageLoader(readerChapter, manga, source, downloadManager, downloadProvider)
        try {
            logcat { "Translation: listing pages of ${chapter.name}" }
            val pages = loader.getPages()
            logcat { "Translation: ${pages.size} pages found" }
            if (pages.isEmpty()) error(context.stringResource(MR.strings.page_list_empty_error))
            queue.markRunning(chapter.id, pages.size)

            val dir = store.chapterDir(chapter.id, variant).apply { mkdirs() }

            pages.forEachIndexed { index, page ->
                if (isStopped) return
                showProgress(chapter.name, index + 1, pages.size)

                val target = store.pageFile(chapter.id, page.index, variant)
                if (target.exists()) {
                    queue.markPage(chapter.id, index + 1, pageMillis = null)
                    return@forEachIndexed
                }

                val started = System.currentTimeMillis()
                val openStream = page.stream ?: return@forEachIndexed
                // Read the whole page once, like the reader does, then work from memory.
                val imageBytes = openStream().use { it.readBytes() }
                val result = translator.translatePage(imageBytes)
                val jpeg = result?.jpeg
                if (jpeg != null) {
                    val tmp = File(dir, target.name + ".tmp")
                    tmp.writeBytes(jpeg)
                    if (!tmp.renameTo(target)) error("Cannot write ${target.name}")
                }
                if (result != null) {
                    // Text sidecar: what was read and how it was translated, for evaluation and
                    // for building per-series glossaries later.
                    val sidecar = PageSidecar(page.index, result.width, result.height, result.blocks)
                    File(dir, "%03d.json".format(page.index)).writeText(json.encodeToString(sidecar))
                }
                val elapsed = System.currentTimeMillis() - started
                logcat { "Translation: page ${page.index} done in $elapsed ms (${jpeg?.size ?: 0} bytes)" }
                queue.markPage(chapter.id, index + 1, elapsed)
            }

            if (!isStopped) {
                store.markDone(chapter.id, variant)
                queue.markDone(chapter.id)
            }
        } finally {
            loader.recycle()
        }
    }

    private fun showProgress(chapterName: String, current: Int, total: Int) {
        context.notify(ID_TRANSLATION_PROGRESS, Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(context.stringResource(MR.strings.translation_notifier_title))
            setContentText(
                context.stringResource(MR.strings.translation_notifier_progress, chapterName, current, total),
            )
            setSmallIcon(R.drawable.ic_translate_24dp)
            setContentIntent(openQueuePendingIntent(context))
            setProgress(total, current, false)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }
    }

    /** What the UI asks for: enough to show the chapter in the queue before the job runs. */
    data class Request(val chapterId: Long, val chapterName: String)

    /** Text of one translated page, written as NNN.json next to NNN.jpg. */
    @Serializable
    data class PageSidecar(
        val page: Int,
        val width: Int,
        val height: Int,
        val blocks: List<PageTranslator.TranslatedBlock>,
    )

    private val json = Json { prettyPrint = true }

    companion object {
        private const val TAG = "ChapterTranslation"
        private const val KEY_MANGA_ID = "manga_id"
        private const val KEY_CHAPTER_IDS = "chapter_ids"
        private const val KEY_MODE = "mode"
        const val MODE_TRANSLATE = "translate"
        const val MODE_EXTRACT = "extract"

        /** Output folder of the extract mode: the human text of a translated edition. */
        fun extractVariant(language: String) = "$language-extract"

        const val ID_TRANSLATION_PROGRESS = -801
        const val ID_TRANSLATION_ERROR = -802

        fun start(
            context: Context,
            mangaId: Long,
            mangaTitle: String,
            requests: List<Request>,
            mode: String = MODE_TRANSLATE,
        ) {
            if (requests.isEmpty()) return
            context.appGraph.translationQueue.enqueue(
                requests.map { TranslationQueue.Item(it.chapterId, mangaId, mangaTitle, it.chapterName) },
            )
            val chapterIds = requests.map { it.chapterId }

            val request = OneTimeWorkRequestBuilder<ChapterTranslationJob>()
                .addTag(TAG)
                // Short, linear retry delay: the default exponential backoff made a job wait
                // minutes after a failure, which looks like "nothing happens" to the user.
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_MANGA_ID to mangaId,
                        KEY_CHAPTER_IDS to chapterIds.toLongArray(),
                        KEY_MODE to mode,
                    ),
                )
                .build()
            val workManager = WorkManager.getInstance(context)
            // Append behind a running job; otherwise replace whatever is left (a job stuck in a
            // retry delay after a crash would keep the new request waiting for minutes).
            val isRunning = workManager.getWorkInfosForUniqueWork(TAG).get()
                .any { it.state == WorkInfo.State.RUNNING }
            val policy = if (isRunning) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
            workManager.enqueueUniqueWork(TAG, policy, request)
        }

        /** Opens the app on the translation queue screen (used by the notifications). */
        fun openQueuePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                action = Constants.SHORTCUT_TRANSLATIONS
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
            context.appGraph.translationQueue.cancelAll()
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(TAG)
                .asFlow()
                .map { list -> list.any { it.state == WorkInfo.State.RUNNING } }
        }
    }
}
