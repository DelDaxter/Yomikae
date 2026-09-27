package mihon.feature.translation.memory

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Yomikae: what the app remembers about one series to translate it consistently.
 *
 * - [referenceMangaId]: another library entry that is the same work already translated by
 *   humans (for example the English edition of a Korean raw). Its text, once extracted, gives
 *   [examples]: aligned bubble pairs (source language -> human translation).
 * - [glossary]: term pairs the user wants enforced (names, places, recurring words).
 * - [lines]: short lines the engine already translated (names, shouts) and how; reused as is
 *   so a name is not rendered three different ways across chapters.
 */
@Serializable
data class SeriesMemory(
    val mangaId: Long,
    val referenceMangaId: Long? = null,
    val glossary: List<TermPair> = emptyList(),
    val examples: List<TermPair> = emptyList(),
    val lines: Map<String, String> = emptyMap(),
    val alignedChapters: Int = 0,
    /**
     * Reference chapter number = source chapter number + offset. Naver numbers its prologue
     * "000." (read as chapter 1) where the English edition has "Episode 0", so the offset is -1.
     */
    val referenceOffset: Double = 0.0,
    val updatedAt: Long = 0,
) {
    @Serializable
    data class TermPair(val source: String, val target: String)

    /**
     * Pairs handed to the translator for one page: the user glossary, then the examples that
     * share words with the page's lines (most shared first), completed with the first stored
     * examples (the most recurring terms of the series) up to [maxExamples].
     */
    fun promptPairs(pageLines: List<String>, maxExamples: Int = MAX_PROMPT_EXAMPLES): List<Pair<String, String>> {
        val pageTokens = pageLines.flatMap { tokens(it) }.toSet()
        val relevant = examples
            .map { pair -> pair to tokens(pair.source).count { it in pageTokens } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
        val chosen = LinkedHashSet<TermPair>()
        chosen += relevant.take(maxExamples * 2 / 3)
        for (pair in examples) {
            if (chosen.size >= maxExamples) break
            chosen += pair
        }
        return glossary.map { it.source to it.target } + chosen.map { it.source to it.target }
    }

    companion object {
        const val MAX_PROMPT_EXAMPLES = 60
        const val MAX_STORED_EXAMPLES = 400
        const val MAX_LINE_LENGTH = 8

        /**
         * A line worth remembering verbatim is a name or a one-word shout: short, no spaces in the
         * source, at most two words in the translation. Longer short lines ("so...", "um, excuse
         * me") depend on the context and must be translated again each time.
         */
        fun isReusableLine(source: String, target: String): Boolean =
            source.length <= MAX_LINE_LENGTH &&
                source.none { it.isWhitespace() } &&
                source.any { it.isLetter() } &&
                target.isNotBlank() &&
                target.trim().split(Regex("\\s+")).size <= 2

        /** Word-like pieces of a line, 2 characters or more, punctuation stripped. */
        fun tokens(text: String): List<String> = text
            .split(' ', '\n', '\t', ',', '.', '?', '!', '…', '~', '\'', '"', '(', ')', '[', ']')
            .map { it.trim() }
            .filter { it.length >= 2 && it.any { c -> c.isLetter() } }
    }
}

/** Reads and writes [SeriesMemory] files: `filesDir/translations/memory/<mangaId>.json`. */
@Inject
@SingleIn(AppScope::class)
class SeriesMemoryStore(
    private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val dir: File
        get() = File(context.filesDir, "translations/memory")

    private fun file(mangaId: Long) = File(dir, "$mangaId.json")

    @Synchronized
    fun load(mangaId: Long): SeriesMemory {
        val f = file(mangaId)
        if (!f.exists()) return SeriesMemory(mangaId)
        return runCatching { json.decodeFromString<SeriesMemory>(f.readText()) }
            .onFailure { logcat(LogPriority.WARN, it) { "Unreadable series memory for $mangaId" } }
            .getOrDefault(SeriesMemory(mangaId))
    }

    @Synchronized
    fun save(memory: SeriesMemory) {
        dir.mkdirs()
        val tmp = File(dir, "${memory.mangaId}.json.tmp")
        tmp.writeText(json.encodeToString(memory.copy(updatedAt = System.currentTimeMillis())))
        tmp.renameTo(file(memory.mangaId))
    }

    /** Read-modify-write under the lock: the job and the memory screen both write here. */
    @Synchronized
    fun update(mangaId: Long, transform: (SeriesMemory) -> SeriesMemory) {
        save(transform(load(mangaId)))
    }

    /** Parses "source = target" lines typed by the user into glossary pairs. */
    fun parseGlossary(text: String): List<SeriesMemory.TermPair> = parseGlossaryText(text)

    companion object {
        /** "source = target" per line; "→" works too; lines starting with "#" are comments. */
        fun parseGlossaryText(text: String): List<SeriesMemory.TermPair> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && ('=' in it || '→' in it) }
            .mapNotNull { line ->
                val sep = if ('→' in line) '→' else '='
                val source = line.substringBefore(sep).trim()
                val target = line.substringAfter(sep).trim()
                if (source.isEmpty() || target.isEmpty()) null else SeriesMemory.TermPair(source, target)
            }
            .toList()
    }

    fun formatGlossary(pairs: List<SeriesMemory.TermPair>): String =
        pairs.joinToString("\n") { "${it.source} = ${it.target}" }
}
