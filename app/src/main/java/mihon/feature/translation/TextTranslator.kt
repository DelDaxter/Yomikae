package mihon.feature.translation

import java.io.Closeable

/**
 * Yomikae: a text translation engine. The first of the "plugin" roles: the page pipeline only
 * knows this interface, and an engine can be ML Kit, a local LLM, a server, or anything else.
 *
 * [translate] receives every text block of one page in reading order and must return exactly
 * one string per input, in the same order. Engines that understand context (LLMs) can use the
 * whole page at once; simpler engines translate line by line.
 */
interface TextTranslator : Closeable {

    /** Called once before the first page (model download, warm-up, connection check). */
    suspend fun prepare()

    suspend fun translate(lines: List<String>): List<String>

    companion object {
        const val ENGINE_MLKIT = "mlkit"

        /** LLM served over the network (llama-server on a PC, hosted API). */
        const val ENGINE_LLM = "llm"

        /** LLM embedded in the app (LiteRT-LM): the target of the project. */
        const val ENGINE_LOCAL = "local"
    }
}
