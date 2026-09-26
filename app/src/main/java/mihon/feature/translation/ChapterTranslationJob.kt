package mihon.feature.translation

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
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

    init {
        graph.inject(this)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = context.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(context.stringResource(MR.strings.translation_notifier_title))
            setSmallIcon(R.drawable.ic_translate_24dp)
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

        val sourceLanguage = preferences.sourceLanguage.get()
        val targetLanguage = preferences.targetLanguage.get()
        val translator = MlKitPageTranslator(sourceLanguage, targetLanguage)
        var failures = 0
        try {
            translator.prepare()

            for (chapterId in chapterIds) {
                if (isStopped) break
                val chapter = getChapter.await(chapterId) ?: continue
                try {
                    translateChapter(translator, manga, source, chapter, sourceLanguage, targetLanguage)
                } catch (e: Exception) {
                    failures++
                    logcat(LogPriority.ERROR, e) { "Translation failed for ${chapter.name}" }
                    context.notify(ID_TRANSLATION_ERROR, Notifications.CHANNEL_DOWNLOADER_ERROR) {
                        setContentTitle(context.stringResource(MR.strings.translation_notifier_error, chapter.name))
                        setContentText(e.message)
                        setSmallIcon(R.drawable.ic_translate_24dp)
                    }
                }
            }
        } catch (e: Exception) {
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
        }

        return if (failures == 0) Result.success() else Result.failure()
    }

    private suspend fun translateChapter(
        translator: MlKitPageTranslator,
        manga: tachiyomi.domain.manga.model.Manga,
        source: eu.kanade.tachiyomi.source.Source,
        chapter: Chapter,
        sourceLanguage: String,
        targetLanguage: String,
    ) {
        // Reuse the reader's own loader so page order is exactly what the reader will show.
        val readerChapter = ReaderChapter(chapter)
        val loader = DownloadPageLoader(readerChapter, manga, source, downloadManager, downloadProvider)
        val pages = try {
            loader.getPages()
        } finally {
            loader.recycle()
        }
        if (pages.isEmpty()) error(context.stringResource(MR.strings.page_list_empty_error))

        val dir = store.chapterDir(chapter.id, sourceLanguage, targetLanguage).apply { mkdirs() }

        pages.forEachIndexed { index, page ->
            if (isStopped) return
            showProgress(chapter.name, index + 1, pages.size)

            val target = store.pageFile(chapter.id, page.index, sourceLanguage, targetLanguage)
            if (target.exists()) return@forEachIndexed

            val openStream = page.stream ?: return@forEachIndexed
            val jpeg = translator.translatePage(openStream) ?: return@forEachIndexed

            val tmp = File(dir, target.name + ".tmp")
            tmp.writeBytes(jpeg)
            if (!tmp.renameTo(target)) error("Cannot write ${target.name}")
        }

        if (!isStopped) store.markDone(chapter.id, sourceLanguage, targetLanguage)
    }

    private fun showProgress(chapterName: String, current: Int, total: Int) {
        context.notify(ID_TRANSLATION_PROGRESS, Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(context.stringResource(MR.strings.translation_notifier_title))
            setContentText(
                context.stringResource(MR.strings.translation_notifier_progress, chapterName, current, total),
            )
            setSmallIcon(R.drawable.ic_translate_24dp)
            setProgress(total, current, false)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }
    }

    companion object {
        private const val TAG = "ChapterTranslation"
        private const val KEY_MANGA_ID = "manga_id"
        private const val KEY_CHAPTER_IDS = "chapter_ids"

        const val ID_TRANSLATION_PROGRESS = -801
        const val ID_TRANSLATION_ERROR = -802

        fun start(context: Context, mangaId: Long, chapterIds: List<Long>) {
            val request = OneTimeWorkRequestBuilder<ChapterTranslationJob>()
                .addTag(TAG)
                .setInputData(
                    workDataOf(
                        KEY_MANGA_ID to mangaId,
                        KEY_CHAPTER_IDS to chapterIds.toLongArray(),
                    ),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
        }
    }
}
