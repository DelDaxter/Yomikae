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

    /** One user prompt in, the model's whole answer out. */
    suspend fun complete(prompt: String): String
}
