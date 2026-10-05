package mihon.feature.translation

import java.io.Closeable

/**
 * Yomikae: where an LLM prompt gets answered. [LlmTranslator] builds the prompts and reads
 * the answers; a backend only has to turn one prompt into one completion. Two backends exist:
 * an OpenAI-compatible server ([HttpLlmBackend]) and the model embedded in the app
 * ([LocalLlmBackend], the real target: everything stays on the phone).
 */
interface LlmBackend : Closeable {

    /** Called once: downloads or loads the model, checks the connection. May take seconds. */
    suspend fun prepare()

    /** One user prompt in, the model's whole answer out (a conversation of its own). */
    suspend fun complete(prompt: String, maxOutputTokens: Int = LlmTranslator.MAX_OUTPUT_TOKENS): String

    /**
     * One turn of a conversation the model remembers: the rules, the references and the
     * previous pages stay in its context, so a turn only carries what is new. On the phone
     * reading the prompt (prefill) is what costs time, and a page prompt with references is
     * ~800 tokens: not re-reading them for every page is the biggest speed lever. [reset]
     * starts a fresh conversation (the first page, or when the context is nearly full).
     */
    suspend fun chat(turn: String, reset: Boolean, maxOutputTokens: Int = LlmTranslator.MAX_OUTPUT_TOKENS): String

    /** Tokens held by the current conversation (0 when none), so the caller resets in time. */
    fun conversationTokens(): Int

    /** Thrown by [chat] when a continuation turn cannot be appended: the caller starts over with a full prompt. */
    class ConversationLostException(cause: Throwable) : RuntimeException(cause)

    /**
     * How many series-memory pairs to put in a page prompt. Every pair costs prompt tokens,
     * and on the phone the prompt is what takes time (prefill), so the embedded model gets
     * fewer than a server.
     */
    val maxReferencePairs: Int
}
