package mihon.feature.translation

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.feature.merge.MangaGroupStore
import mihon.feature.translation.ocr.OcrEngine
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
    private val groupStore: MangaGroupStore,
) {

    /**
     * Target language for a chapter of [mangaId]: the reading language of its series (the
     * primary entry when it belongs to a unified entry) or the global one, see
     * [TranslationPreferences.translationTarget].
     */
    fun targetFor(mangaId: Long?): String {
        if (mangaId == null) return preferences.targetLanguage.get()
        val primary = groupStore.groupOfMember(mangaId)?.primaryMangaId ?: mangaId
        return preferences.translationTarget(primary)
    }

    private val root: File
        get() = File(context.filesDir, "translations")

    /**
     * Name of the output folder for the current settings: "<source>-<target>" for ML Kit (the
     * original layout) and "<source>-<target>-<engine>" for the other engines, so switching
     * engine never shows a page made by another one.
     */
    fun currentVariant(mangaId: Long? = null): String {
        val base = "${preferences.sourceLanguage.get()}-${targetFor(mangaId)}"
        val engine = preferences.engine.get()
        val ocr = preferences.ocrEngine.get()
        val withEngine = if (engine == TextTranslator.ENGINE_MLKIT) base else "$base-$engine"
        return if (ocr == OcrEngine.ENGINE_MLKIT) withEngine else "$withEngine-$ocr"
    }

    fun chapterDir(chapterId: Long, variant: String): File = File(root, "$chapterId/$variant")

    /**
     * Folder holding text sidecars of a chapter translated FROM [sourceLanguage] (any engine,
     * any target): the one with the most pages wins. Null when nothing was translated yet.
     */
    fun sidecarDir(chapterId: Long, sourceLanguage: String): File? =
        File(root, chapterId.toString()).listFiles { f -> f.isDirectory && f.name.startsWith("$sourceLanguage-") }
            ?.filter { dir -> dir.name != "$sourceLanguage-extract" }
            ?.maxByOrNull { dir -> dir.listFiles { f -> f.extension == "json" }?.size ?: 0 }
            ?.takeIf { dir -> dir.listFiles { f -> f.extension == "json" }?.isNotEmpty() == true }

    fun pageFile(chapterId: Long, pageIndex: Int, variant: String): File =
        File(chapterDir(chapterId, variant), "%03d.jpg".format(pageIndex))

    private fun doneMarker(chapterId: Long, variant: String): File = File(chapterDir(chapterId, variant), ".done")

    fun markDone(chapterId: Long, variant: String) {
        doneMarker(chapterId, variant).createNewFile()
    }

    /** True when the chapter has been fully translated with the current settings. */
    fun isChapterTranslated(chapterId: Long, mangaId: Long?): Boolean =
        doneMarker(chapterId, currentVariant(mangaId)).exists()

    /** True when at least one page of the chapter is translated with the current settings. */
    fun hasAnyTranslatedPage(chapterId: Long, mangaId: Long?): Boolean {
        val dir = chapterDir(chapterId, currentVariant(mangaId))
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
    fun applyTo(chapterId: Long, mangaId: Long?, pages: List<ReaderPage>) {
        pages.forEach { page ->
            val original = page.stream ?: return@forEach
            page.stream = { openTranslatedOrOriginal(chapterId, mangaId, page.index, original) }
        }
    }

    private fun openTranslatedOrOriginal(
        chapterId: Long,
        mangaId: Long?,
        pageIndex: Int,
        original: () -> InputStream,
    ): InputStream {
        if (!preferences.showTranslated.get()) return original()
        val file = pageFile(chapterId, pageIndex, currentVariant(mangaId))
        return if (file.exists()) file.inputStream() else original()
    }
}
