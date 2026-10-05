package mihon.feature.translation

import logcat.LogPriority
import mihon.feature.translation.memory.SeriesMemory
import tachiyomi.core.common.util.system.logcat
import java.util.Locale

/**
 * Yomikae: translation through a large language model. The model itself lives behind
 * [LlmBackend]: embedded in the app ([LocalLlmBackend]) or on a server ([HttpLlmBackend]).
 *
 * Why an LLM: it receives the whole page at once, plus a glossary and background about the
 * series, so names stay consistent and the tone fits comics dialogue. That is what a
 * line-by-line engine like ML Kit cannot do.
 *
 * Prompt shape follows what translation models such as Hy-MT2 are trained on: an optional
 * "reference the following translations" block (the glossary), a "background information"
 * block, then the numbered lines to translate.
 */
class LlmTranslator(
    private val backend: LlmBackend,
    private val targetLanguage: String,
    private val background: String,
    /** Series memory: glossary and human examples, selected per page. Null = none. */
    private val memory: SeriesMemory? = null,
    /** Lines already translated for this series (names, shouts): reused verbatim. */
    knownLines: Map<String, String> = emptyMap(),
    /** Glossary shared by every series; only the entries found on the page are sent. */
    private val globalGlossary: List<Pair<String, String>> = emptyList(),
    /** Keep Korean forms of address (hyung, noona, -nim…) rather than adapting them. */
    private val keepHonorifics: Boolean = true,
) : TextTranslator {

    // Lines stored by earlier versions are filtered again: only names and shouts are reused.
    private val cache = HashMap<String, String>(knownLines.filter { (k, v) -> SeriesMemory.isReusableLine(k, v) })

    /** What this run translated, so the job can remember the short lines afterwards. */
    val translated: Map<String, String> get() = cache

    /** Conversation state: references already in the model's context, turns since the last reset. */
    private val sentPairs = HashSet<String>()
    private var turns = 0

    override suspend fun prepare() {
        backend.prepare()
    }

    override suspend fun translate(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val result = arrayOfNulls<String>(lines.size)
        lines.forEachIndexed { i, line -> cache[line]?.let { result[i] = it } }

        val missing = lines.indices.filter { result[it] == null }
        if (missing.isNotEmpty()) {
            val batch = missing.map { lines[it] }
            val translated = translateBatch(batch)
            missing.forEachIndexed { k, i ->
                val text = translated[k] ?: translateSingle(lines[i])
                cache[lines[i]] = text
                result[i] = text
            }
        }
        return result.map { it.orEmpty() }
    }

    /** Whole page in one prompt; returns null for lines the model did not answer well. */
    private suspend fun translateBatch(lines: List<String>): List<String?> {
        val seriesPairs = memory?.promptPairs(lines, backend.maxReferencePairs).orEmpty()
        val seriesSources = seriesPairs.map { it.first }.toSet()
        val globalPairs = globalGlossary.filter { (source, _) ->
            source !in seriesSources && lines.any { it.contains(source) }
        }
        val pairs = globalPairs + seriesPairs
        val answers = askForPage(lines, pairs)
        // A small model sometimes answers a bubble with the translation of one of the examples
        // instead of its own. Such a line is dropped and translated alone, without examples.
        val exampleTargets = pairs.associate { (from, to) -> to.trim().lowercase() to from }
        return answers.mapIndexed { i, text ->
            val copiedFrom = text?.let { exampleTargets[it.trim().lowercase()] }
            if (copiedFrom != null && copiedFrom != lines[i]) {
                logcat(LogPriority.INFO) { "LLM copied an example for \"${lines[i]}\", retrying alone" }
                null
            } else {
                text
            }
        }
    }

    /**
     * One page = one turn of a conversation that lasts the chapter. The first turn carries the
     * references, the background and the rules; the next ones only the new references and the
     * page ("Next page, same rules"). The model keeps the earlier turns in its context, which
     * also helps consistency between pages (measured on the PC bench: +0.01). The conversation
     * restarts when its context nears the window ([CONTEXT_RESET_TOKENS]) or after [MAX_TURNS].
     */
    private suspend fun askForPage(lines: List<String>, pairs: List<Pair<String, String>>): List<String?> {
        val reset = turns == 0 || turns >= MAX_TURNS || backend.conversationTokens() > CONTEXT_RESET_TOKENS
        val newPairs = if (reset) pairs else pairs.filter { it.first !in sentPairs }
        val prompt = buildString {
            if (newPairs.isNotEmpty()) {
                appendLine(if (reset) "Reference the following translations:" else "Also reference these translations:")
                newPairs.forEach { (from, to) -> appendLine("`$from` translates to `$to`") }
                appendLine()
            }
            if (reset) {
                appendLine("[Background Information]")
                appendLine(background.ifBlank { defaultBackground(targetLanguage) })
                appendLine()
                appendLine(
                    "Please accurately translate the following text into ${languageName(targetLanguage)}, " +
                        "taking the provided background information into consideration. " +
                        "Each line below is one speech bubble and starts with its number and a vertical bar. " +
                        "Translate every line, keep exactly the same number of lines, and start each translated " +
                        "line with the same number and vertical bar as its source line. " +
                        "A line that is only a sound effect or onomatopoeia becomes a short comic-book sound " +
                        "effect in capitals (KEKEKE, ACK!, WHOOSH), not a description. " +
                        (if (keepHonorifics) HONORIFICS_RULE else "") +
                        "Only output the translated lines without any additional explanation. " +
                        "I will send the pages one by one; answer each page the same way.",
                )
            } else {
                appendLine("Next page, same rules:")
            }
            appendLine()
            lines.forEachIndexed { i, line -> appendLine("${i + 1}| ${line.replace('\n', ' ')}") }
        }

        val answer = try {
            backend.chat(prompt, reset)
        } catch (e: LlmBackend.ConversationLostException) {
            // The runtime refused to continue the conversation: start a new one with everything.
            logcat(LogPriority.WARN, e) { "LLM conversation lost, restarting it" }
            turns = 0
            return askForPage(lines, pairs)
        }
        if (reset) sentPairs.clear()
        sentPairs += pairs.map { it.first }
        turns = if (reset) 1 else turns + 1
        val byNumber = HashMap<Int, String>()
        val numbered = ArrayList<String>()
        val plain = ArrayList<String>()
        answer.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { raw ->
            val match = LINE_PATTERN.matchEntire(raw)
            if (match != null) {
                val text = match.groupValues[2].trim().trim('`', '"')
                match.groupValues[1].toIntOrNull()?.let { byNumber[it] = text }
                numbered += text
            }
            plain += (match?.groupValues?.get(2) ?: raw).trim().trim('`', '"')
        }
        val ordered = when {
            byNumber.size == lines.size -> return lines.indices.map { byNumber[it + 1]?.takeIf { t -> t.isNotBlank() } }
            // The model kept the prefixes on some lines only: a preamble or a stray sentence is
            // ignored, the numbered lines are taken in order.
            numbered.size == lines.size -> numbered
            // No prefix at all (the small model often drops them on one or two lines): the answer
            // has as many lines as the question, trust the order.
            byNumber.isEmpty() && plain.size == lines.size -> plain
            else -> {
                logcat(LogPriority.WARN) {
                    "LLM answered ${plain.size} lines (${byNumber.size} numbered) for ${lines.size}: ${answer.take(
                        200,
                    )}"
                }
                return lines.indices.map { byNumber[it + 1]?.takeIf { t -> t.isNotBlank() } }
            }
        }
        return ordered.map { it.takeIf { text -> text.isNotBlank() } }
    }

    private suspend fun translateSingle(line: String): String {
        val prompt = "Translate the following text into ${languageName(targetLanguage)}. " +
            "Note that you should only output the translated result without any additional explanation:\n\n$line"
        return backend.complete(prompt).trim().trim('`', '"')
    }

    override fun close() {
        backend.close()
    }

    companion object {
        /** Sampling shared by every backend: low temperature, translations must not drift. */
        const val TEMPERATURE = 0.3
        const val TOP_P = 0.6
        const val TOP_K = 20
        const val MAX_OUTPUT_TOKENS = 1024

        /** The embedded model has a 4096-token window; a full first turn is under 1000. */
        private const val CONTEXT_RESET_TOKENS = 2800
        private const val MAX_TURNS = 20

        private val LINE_PATTERN = Regex("""^([0-9N]+)\s*[|｜]\s*(.*)$""")
        private const val HONORIFICS_RULE =
            "Keep Korean forms of address as fans expect them (hyung, noona, oppa, unnie, sunbae, ahjussi, " +
                "-nim, -ssi) instead of replacing them with titles of the target language. "

        /**
         * Background in the target language: Hy-MT2 follows the register of this block, and an
         * English one ("natural spoken English") is the wrong hint for a French translation.
         */
        private fun defaultBackground(target: String): String = when (target) {
            "fr" ->
                "Ce sont les bulles d'une page d'un webtoon coréen, dans l'ordre de lecture. " +
                    "Utilise un français oral naturel, comme dans les scans et les éditions françaises de webtoons. " +
                    "Garde les noms des personnages identiques d'une page à l'autre."
            else ->
                "These are the speech bubbles of one page of a Korean webtoon, in reading order. " +
                    "Use natural spoken English as in published comics. Keep character names consistent."
        }

        private fun languageName(tag: String): String =
            Locale(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { tag }
    }
}
