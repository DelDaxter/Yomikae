package mihon.feature.translation

/** Yomikae: what the chapter list knows about a chapter's translation. */
enum class TranslationState {
    NONE,
    QUEUED,
    RUNNING,
    DONE,
    ERROR,
}
