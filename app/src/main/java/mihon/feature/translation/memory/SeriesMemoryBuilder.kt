package mihon.feature.translation.memory

import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.feature.translation.ChapterTranslationJob
import mihon.feature.translation.TranslationStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Yomikae: builds the aligned examples of a [SeriesMemory] from two editions of the same work.
 *
 * Both editions are the same vertical strip, resized and cut into pages differently. So every
 * page's text boxes are brought to a common width, page heights are accumulated into one
 * "strip" coordinate, and boxes of the two editions that overlap are the same bubble.
 *
 * Input: the text sidecars (NNN.json) the translation job writes: for the source edition
 * (source text = the raw language) and for the reference edition (extract mode: its text is
 * the human translation).
 */
@Inject
class SeriesMemoryBuilder(
    private val store: TranslationStore,
    private val memoryStore: SeriesMemoryStore,
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {
    private val json = Json { ignoreUnknownKeys = true }

    class Report(
        val chaptersAligned: Int,
        val pairs: Int,
        val chaptersWithoutReferenceText: Int,
        /** Offset found and saved by [detectOffset] during this run, null when unchanged. */
        val offsetDetected: Double? = null,
    )

    /**
     * Finds the chapter-number offset between the two editions from the height of their
     * pages: two editions of the same episode are the same vertical strip, so their total
     * heights match to within one percent, whatever the numbering. Returns the offset that
     * pairs the most chapters, null when nothing better than the current one is found.
     */
    suspend fun detectOffset(mangaId: Long, sourceLanguage: String, targetLanguage: String): Double? {
        val memory = memoryStore.load(mangaId)
        val referenceId = memory.referenceMangaId ?: return null
        val sourceHeights = getChaptersByMangaId.await(mangaId).mapNotNull { chapter ->
            store.sidecarDir(chapter.id, sourceLanguage)?.let { stripHeight(it) }?.let { chapter.chapterNumber to it }
        }
        val referenceHeights = getChaptersByMangaId.await(referenceId).mapNotNull { chapter ->
            stripHeight(store.chapterDir(chapter.id, ChapterTranslationJob.extractVariant(targetLanguage)))
                ?.let { chapter.chapterNumber to it }
        }
        if (sourceHeights.isEmpty() || referenceHeights.isEmpty()) return null
        val referenceByNumber = referenceHeights.associate { numberKey(it.first) to it.second }

        fun matches(offset: Double): Int = sourceHeights.count { (number, height) ->
            val other = referenceByNumber[numberKey(number + offset)] ?: return@count false
            kotlin.math.abs(other - height) <= height * 0.01f
        }

        val current = memory.referenceOffset
        var best = current
        var bestMatches = matches(current)
        for (candidate in CANDIDATE_OFFSETS) {
            if (candidate == current) continue
            val m = matches(candidate)
            if (m > bestMatches) {
                best = candidate
                bestMatches = m
            }
        }
        if (best == current || bestMatches < 1) return null
        memoryStore.update(mangaId) { it.copy(referenceOffset = best) }
        logcat { "Series memory $mangaId: chapter offset $best detected ($bestMatches matching chapters)" }
        return best
    }

    private fun numberKey(number: Double): Long = kotlin.math.round(number * 100).toLong()

    /** Total height of a chapter's pages at [COMMON_WIDTH], from its sidecars; null without any. */
    private fun stripHeight(dir: File): Float? {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return null
        if (files.isEmpty()) return null
        var height = 0f
        for (file in files) {
            val page = runCatching { json.decodeFromString<ChapterTranslationJob.PageSidecar>(file.readText()) }
                .getOrNull() ?: continue
            height += page.height * COMMON_WIDTH / page.width
        }
        return height.takeIf { it > 0f }
    }

    /**
     * Aligns every chapter of [mangaId] that has source-language sidecars with the chapter of
     * the same number in the reference edition, and stores the resulting examples.
     */
    suspend fun rebuild(mangaId: Long, sourceLanguage: String, targetLanguage: String): Report {
        // A numbering offset the user did not set is looked for first (Naver's "000." prologue).
        val offsetDetected = if (memoryStore.load(mangaId).referenceOffset == 0.0) {
            detectOffset(mangaId, sourceLanguage, targetLanguage)
        } else {
            null
        }
        val memory = memoryStore.load(mangaId)
        val referenceId = memory.referenceMangaId ?: return Report(0, 0, 0)

        val sourceChapters = getChaptersByMangaId.await(mangaId).associateBy { it.chapterNumber }
        val referenceChapters = getChaptersByMangaId.await(referenceId).associateBy { it.chapterNumber }

        val examples = ArrayList<SeriesMemory.TermPair>()
        var aligned = 0
        var missingReference = 0
        for ((number, chapter) in sourceChapters) {
            val reference = referenceChapters[number + memory.referenceOffset] ?: continue
            val sourceDir = store.sidecarDir(chapter.id, sourceLanguage) ?: continue
            val referenceDir = store.chapterDir(reference.id, ChapterTranslationJob.extractVariant(targetLanguage))
            if (!referenceDir.isDirectory) {
                missingReference++
                continue
            }
            val pairs = align(loadStrip(sourceDir), loadStrip(referenceDir))
            if (pairs.isNotEmpty()) aligned++
            examples += pairs
        }

        // Pairs whose words come back often across the series (names, places, recurring
        // terms) first, shorter ones before longer ones; the translator then picks, for each
        // page, the pairs sharing words with that page.
        val distinct = examples.distinctBy { it.source }
        val frequency = HashMap<String, Int>()
        distinct.forEach { pair ->
            SeriesMemory.tokens(pair.source).forEach { frequency[it] = (frequency[it] ?: 0) + 1 }
        }
        val kept = distinct
            .sortedWith(
                compareByDescending<SeriesMemory.TermPair> { pair ->
                    SeriesMemory.tokens(pair.source).count { (frequency[it] ?: 0) >= 2 }
                }.thenBy { it.source.length + it.target.length },
            )
            .take(SeriesMemory.MAX_STORED_EXAMPLES)
        // Through update(): the glossary the user may have edited meanwhile is kept.
        memoryStore.update(mangaId) { it.copy(examples = kept, alignedChapters = aligned) }
        logcat { "Series memory $mangaId: $aligned chapters aligned, ${kept.size} pairs" }
        return Report(aligned, kept.size, missingReference, offsetDetected)
    }

    // ---- alignment ----

    private class Block(val source: String, val target: String, val l: Float, val t: Float, val r: Float, val b: Float)

    private fun loadStrip(dir: File): List<Block> {
        val files = dir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name } ?: return emptyList()
        val blocks = ArrayList<Block>()
        var offset = 0f
        for (file in files) {
            val page = runCatching { json.decodeFromString<ChapterTranslationJob.PageSidecar>(file.readText()) }
                .onFailure { logcat(LogPriority.WARN, it) { "Bad sidecar ${file.name}" } }
                .getOrNull() ?: continue
            val scale = COMMON_WIDTH / page.width
            for (b in page.blocks) {
                blocks += Block(
                    b.source,
                    b.target,
                    b.left * scale,
                    offset + b.top * scale,
                    b.right * scale,
                    offset + b.bottom * scale,
                )
            }
            offset += page.height * scale
        }
        return blocks
    }

    private fun iou(a: Block, b: Block): Float {
        val w = min(a.r, b.r) - max(a.l, b.l)
        val h = min(a.b, b.b) - max(a.t, b.t)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val areaA = (a.r - a.l) * (a.b - a.t)
        val areaB = (b.r - b.l) * (b.b - b.t)
        return inter / (areaA + areaB - inter)
    }

    private fun align(source: List<Block>, reference: List<Block>): List<SeriesMemory.TermPair> {
        val pairs = ArrayList<SeriesMemory.TermPair>()
        for (sb in source) {
            var best: Block? = null
            var bestIou = 0f
            for (rb in reference) {
                val v = iou(sb, rb)
                if (v > bestIou) {
                    best = rb
                    bestIou = v
                }
            }
            if (best == null || bestIou < MIN_IOU) continue
            val from = sb.source.trim()
            val to = best.source.trim() // extract mode: source == human text
            if (from.length < 3 || to.split(' ').size < 2 || to.length > MAX_TARGET_LENGTH) continue
            pairs += SeriesMemory.TermPair(from, to)
        }
        return pairs
    }

    private companion object {
        const val COMMON_WIDTH = 720f
        private val CANDIDATE_OFFSETS = listOf(-1.0, 1.0, -2.0, 2.0, -3.0, 3.0, -0.5, 0.5, 0.0)
        const val MIN_IOU = 0.4f
        const val MAX_TARGET_LENGTH = 90
    }
}
