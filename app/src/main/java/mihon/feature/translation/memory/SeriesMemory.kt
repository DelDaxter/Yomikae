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
    val updatedAt: Long = 0,
) {
    @Serializable
    data class TermPair(val source: String, val target: String)

    /** Pairs handed to the translator: user glossary first, then the human examples. */
    fun promptPairs(maxExamples: Int = MAX_PROMPT_EXAMPLES): List<Pair<String, String>> =
        glossary.map { it.source to it.target } + examples.take(maxExamples).map { it.source to it.target }

    companion object {
        const val MAX_PROMPT_EXAMPLES = 60
        const val MAX_STORED_EXAMPLES = 400
        const val MAX_LINE_LENGTH = 8
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

    fun load(mangaId: Long): SeriesMemory {
        val f = file(mangaId)
        if (!f.exists()) return SeriesMemory(mangaId)
        return runCatching { json.decodeFromString<SeriesMemory>(f.readText()) }
            .onFailure { logcat(LogPriority.WARN, it) { "Unreadable series memory for $mangaId" } }
            .getOrDefault(SeriesMemory(mangaId))
    }

    fun save(memory: SeriesMemory) {
        dir.mkdirs()
        val tmp = File(dir, "${memory.mangaId}.json.tmp")
        tmp.writeText(json.encodeToString(memory.copy(updatedAt = System.currentTimeMillis())))
        tmp.renameTo(file(memory.mangaId))
    }

    fun update(mangaId: Long, transform: (SeriesMemory) -> SeriesMemory) {
        save(transform(load(mangaId)))
    }

    /** Parses "source = target" lines typed by the user into glossary pairs. */
    fun parseGlossary(text: String): List<SeriesMemory.TermPair> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && ('=' in it || '→' in it) }
        .mapNotNull { line ->
            val sep = if ('→' in line) '→' else '='
            val source = line.substringBefore(sep).trim()
            val target = line.substringAfter(sep).trim()
            if (source.isEmpty() || target.isEmpty()) null else SeriesMemory.TermPair(source, target)
        }
        .toList()

    fun formatGlossary(pairs: List<SeriesMemory.TermPair>): String =
        pairs.joinToString("\n") { "${it.source} = ${it.target}" }
}
