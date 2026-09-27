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

    /**
     * Name of the output folder for the current settings: "<source>-<target>" for ML Kit (the
     * original layout) and "<source>-<target>-<engine>" for the other engines, so switching
     * engine never shows a page made by another one.
     */
    fun currentVariant(): String {
        val base = "${preferences.sourceLanguage.get()}-${preferences.targetLanguage.get()}"
        val engine = preferences.engine.get()
        return if (engine == TextTranslator.ENGINE_MLKIT) base else "$base-$engine"
    }

    fun chapterDir(chapterId: Long, variant: String): File = File(root, "$chapterId/$variant")

    fun pageFile(chapterId: Long, pageIndex: Int, variant: String): File =
        File(chapterDir(chapterId, variant), "%03d.jpg".format(pageIndex))

    private fun doneMarker(chapterId: Long, variant: String): File = File(chapterDir(chapterId, variant), ".done")

    fun markDone(chapterId: Long, variant: String) {
        doneMarker(chapterId, variant).createNewFile()
    }

    /** True when the chapter has been fully translated with the current settings. */
    fun isChapterTranslated(chapterId: Long): Boolean = doneMarker(chapterId, currentVariant()).exists()

    /** True when at least one page of the chapter is translated with the current settings. */
    fun hasAnyTranslatedPage(chapterId: Long): Boolean {
        val dir = chapterDir(chapterId, currentVariant())
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
        val file = pageFile(chapterId, pageIndex, currentVariant())
        return if (file.exists()) file.inputStream() else original()
    }
}
