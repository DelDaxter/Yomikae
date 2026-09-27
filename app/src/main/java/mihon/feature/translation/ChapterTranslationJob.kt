package mihon.feature.translation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.asFlow
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
import kotlinx.coroutines.CancellationException
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

    /** The engines built for one manga and mode, reused across its chapters. */
    private class Engines(val page: PageTranslator, val text: TextTranslator)

    override suspend fun doWork(): Result {
        setForegroundSafely()
        queue.recoverAfterRestart()

        var failures = 0
        val engines = HashMap<String, Engines>()
        try {
            while (!isStopped) {
                val item = queue.nextPending() ?: break
                try {
                    processItem(item, engines)
                } catch (e: CancellationException) {
                    // WorkManager stopped us: the chapter goes back to the queue, not to "failed".
                    queue.markPending(item.chapterId)
                    throw e
                } catch (e: Throwable) {
                    // Throwable and not Exception: an OutOfMemoryError on a huge page must mark
                    // the chapter failed and let the queue move on, not leave it "running" forever.
                    failures++
                    queue.markError(item.chapterId, e.message)
                    logcat(LogPriority.ERROR, e) { "Translation failed for ${item.chapterName}" }
                    context.notify(ID_TRANSLATION_ERROR, Notifications.CHANNEL_DOWNLOADER_ERROR) {
                        setContentTitle(context.stringResource(MR.strings.translation_notifier_error, item.chapterName))
                        setContentText(e.message)
                        setSmallIcon(R.drawable.ic_translate_24dp)
                    }
                }
            }
        } finally {
            engines.values.forEach { runCatching { it.page.close() } }
            context.cancelNotification(ID_TRANSLATION_PROGRESS)
            if (isStopped) queue.recoverAfterRestart()
        }
        return if (failures == 0) Result.success() else Result.failure()
    }

    private suspend fun processItem(item: TranslationQueue.Item, engines: HashMap<String, Engines>) {
        val manga = getManga.await(item.mangaId) ?: error("Manga ${item.mangaId} not found")
        val source = sourceManager.get(manga.source) ?: error("Source ${manga.source} not installed")
        val chapter = getChapter.await(item.chapterId) ?: error("Chapter ${item.chapterId} not found")

        val mode = item.mode
        val sourceLanguage = preferences.sourceLanguage.get()
        val targetLanguage = preferences.targetLanguage.get()
        // Extract mode reads a human-translated edition: its pages are in the target language.
        val extractOnly = mode == MODE_EXTRACT || sourceLanguage == targetLanguage
        val ocrLanguage = if (mode == MODE_EXTRACT) targetLanguage else sourceLanguage
        val variant = if (mode == MODE_EXTRACT) extractVariant(targetLanguage) else store.currentVariant()

        val engineSet = engines.getOrPut("${manga.id}:$mode:$variant") {
            // Series memory: human examples, user glossary and lines already translated.
            val memory = memoryStore.load(manga.id)
            if (!extractOnly && memory.referenceMangaId != null) {
                runCatching { memoryBuilder.rebuild(manga.id, sourceLanguage, targetLanguage) }
                    .onFailure { logcat(LogPriority.WARN, it) { "Series memory rebuild failed" } }
            }
            val freshMemory = memoryStore.load(manga.id)
            val text = if (extractOnly) {
                IdentityTranslator()
            } else {
                createTextTranslator(
                    sourceLanguage,
                    targetLanguage,
                    freshMemory,
                )
            }
            val page =
                PageTranslator(
                    createOcrEngine(ocrLanguage),
                    text,
                    sourceLanguage = ocrLanguage,
                    renderPages = !extractOnly,
                )
            page.prepare()
            Engines(page, text)
        }

        val finished = translateChapter(engineSet.page, manga, source, chapter, variant)
        val text = engineSet.text
        if (finished && text is LlmTranslator) rememberShortLines(manga.id, text.translated)
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
        val backend = when (preferences.engine.get()) {
            TextTranslator.ENGINE_LOCAL -> LocalLlmBackend(
                context,
                useGpu = preferences.localLlmGpu.get(),
                onDownloadProgress = { name, done, total ->
                    logcat { "Model $name: ${done / 1_000_000} / ${total / 1_000_000} MB" }
                },
            )
            TextTranslator.ENGINE_LLM -> HttpLlmBackend(
                serverUrl = preferences.llmServerUrl.get(),
                model = preferences.llmModel.get(),
            )
            else -> return MlKitTranslator(sourceLanguage, targetLanguage)
        }
        return LlmTranslator(
            backend = backend,
            targetLanguage = targetLanguage,
            background = preferences.llmBackground.get(),
            memory = memory,
            knownLines = memory.lines,
        )
    }

    /** Keeps how short lines (names, shouts) were rendered, so later chapters reuse them. */
    private fun rememberShortLines(mangaId: Long, translated: Map<String, String>) {
        val short = translated.filter { (k, v) -> SeriesMemory.isReusableLine(k, v) }
        if (short.isEmpty()) return
        memoryStore.update(mangaId) { it.copy(lines = it.lines + short) }
    }

    /** Returns true when the chapter is complete, false when it yielded (stop, cancel, "translate now"). */
    private suspend fun translateChapter(
        translator: PageTranslator,
        manga: tachiyomi.domain.manga.model.Manga,
        source: eu.kanade.tachiyomi.source.Source,
        chapter: Chapter,
        variant: String,
    ): Boolean {
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

            // Pages are strictly sequential. Reading page N+1 (OCR on the CPU) while page N is
            // translated was tried and measured slower on a Galaxy S26 (4.3 s of translation per
            // page instead of 2.5): ONNX Runtime takes every core and the GPU model still needs
            // the CPU for tokenizing and sampling. Not worth 0.2 s of OCR.
            for ((index, page) in pages.withIndex()) {
                if (isStopped || queue.shouldYield(chapter.id)) {
                    if (!queue.isCancelled(chapter.id)) queue.markPending(chapter.id)
                    return false
                }
                showProgress(chapter.name, index + 1, pages.size)

                val target = store.pageFile(chapter.id, page.index, variant)
                if (target.exists()) {
                    queue.markPage(chapter.id, index + 1, pageMillis = null)
                    continue
                }

                val started = System.currentTimeMillis()
                val openStream = page.stream ?: continue
                // Read the whole page once, like the reader does, then work from memory.
                val prepared = translator.read(openStream().use { it.readBytes() })
                val result = prepared?.let { translator.finish(it) }
                writePage(dir, target, page.index, result, chapter, queue.isCancelled(chapter.id)) ?: return false
                val elapsed = System.currentTimeMillis() - started
                logcat { "Translation: page ${page.index} done in $elapsed ms (${result?.jpeg?.size ?: 0} bytes)" }
                queue.markPage(chapter.id, index + 1, elapsed)
            }

            if (isStopped) {
                queue.markPending(chapter.id)
                return false
            }
            store.markDone(chapter.id, variant)
            queue.markDone(chapter.id)
            return true
        } finally {
            loader.recycle()
        }
    }

    /**
     * Stores the translated page and its text sidecar. Returns null when the chapter was removed
     * from the queue while the page was in flight (its folder is gone, that is not an error).
     */
    private fun writePage(
        dir: File,
        target: File,
        pageIndex: Int,
        result: PageTranslator.Result?,
        chapter: Chapter,
        cancelled: Boolean,
    ): Unit? {
        val jpeg = result?.jpeg
        try {
            if (jpeg != null) {
                val tmp = File(dir, target.name + ".tmp")
                tmp.writeBytes(jpeg)
                if (!tmp.renameTo(target)) error("Cannot write ${target.name}")
            }
            if (result != null) {
                // Text sidecar: what was read and how it was translated, for evaluation and
                // for building per-series glossaries later.
                val sidecar = PageSidecar(pageIndex, result.width, result.height, result.blocks)
                File(dir, "%03d.json".format(pageIndex)).writeText(json.encodeToString(sidecar))
            }
        } catch (e: Exception) {
            // "Remove" deletes the chapter folder while a page is in flight: not an error.
            if (cancelled || queue.isCancelled(chapter.id)) return null
            throw e
        }
        return Unit
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
                requests.map { TranslationQueue.Item(it.chapterId, mangaId, mangaTitle, it.chapterName, mode) },
            )
            ensureRunning(context)
        }

        /** Makes sure a worker is alive to drain the queue (a running one picks new items up). */
        fun ensureRunning(context: Context) {
            // APPEND_OR_REPLACE: if a worker is running, a second one is chained after it (and
            // exits at once when the queue is empty); if the previous one failed or was
            // cancelled, the new one replaces it. No blocking state query on the main thread,
            // and no window where a chapter enqueued while the worker exits is left waiting.
            val request = OneTimeWorkRequestBuilder<ChapterTranslationJob>()
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
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
