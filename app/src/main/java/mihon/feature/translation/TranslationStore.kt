package mihon.feature.translation

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import java.io.File
import java.io.InputStream

/**
 * Yomikae: where translated pages live on disk.
 *
 * Layout (app-private storage, so it works for folder and CBZ downloads alike):
 *
 *     filesDir/translations/<chapterId>/<source>-<target>/<pageIndex>.jpg
 *     filesDir/translations/<chapterId>/<source>-<target>/.done   (written when every page is done)
 */
@Inject
@SingleIn(AppScope::class)
class TranslationStore(
    private val context: Context,
    private val preferences: TranslationPreferences,
) {

    private val root: File
        get() = File(context.filesDir, "translations")

    fun chapterDir(chapterId: Long, source: String, target: String): File =
        File(root, "$chapterId/$source-$target")

    fun pageFile(chapterId: Long, pageIndex: Int, source: String, target: String): File =
        File(chapterDir(chapterId, source, target), "%03d.jpg".format(pageIndex))

    private fun doneMarker(chapterId: Long, source: String, target: String): File =
        File(chapterDir(chapterId, source, target), ".done")

    fun markDone(chapterId: Long, source: String, target: String) {
        doneMarker(chapterId, source, target).createNewFile()
    }

    /** True when the chapter has been fully translated for the languages currently selected. */
    fun isChapterTranslated(chapterId: Long): Boolean =
        doneMarker(chapterId, preferences.sourceLanguage.get(), preferences.targetLanguage.get()).exists()

    /** True when at least one page of the chapter is translated for the current languages. */
    fun hasAnyTranslatedPage(chapterId: Long): Boolean {
        val dir = chapterDir(chapterId, preferences.sourceLanguage.get(), preferences.targetLanguage.get())
        return dir.listFiles { f -> f.extension == "jpg" }?.isNotEmpty() == true
    }

    fun deleteChapter(chapterId: Long) {
        File(root, chapterId.toString()).deleteRecursively()
    }

    /**
     * Wraps each page's stream so that the reader gets the translated image when one exists and
     * the user asked for translated pages. The decision is taken at read time, so toggling the
     * preference and reloading the viewer is enough to switch between original and translation.
     */
    fun applyTo(chapterId: Long, pages: List<ReaderPage>) {
        pages.forEach { page ->
            val original = page.stream ?: return@forEach
            page.stream = { openTranslatedOrOriginal(chapterId, page.index, original) }
        }
    }

    private fun openTranslatedOrOriginal(
        chapterId: Long,
        pageIndex: Int,
        original: () -> InputStream,
    ): InputStream {
        if (!preferences.showTranslated.get()) return original()
        val file = pageFile(
            chapterId,
            pageIndex,
            preferences.sourceLanguage.get(),
            preferences.targetLanguage.get(),
        )
        return if (file.exists()) file.inputStream() else original()
    }
}
