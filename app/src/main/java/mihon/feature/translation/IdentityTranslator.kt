package mihon.feature.translation

/**
 * Yomikae: no translation at all. Used when source and target languages are the same: the
 * job then only reads the pages (OCR) and writes the text sidecars, which is how the text of
 * an already-translated edition is extracted to build a series glossary.
 */
class IdentityTranslator : TextTranslator {
    override suspend fun prepare() = Unit
    override suspend fun translate(lines: List<String>): List<String> = lines
    override fun close() = Unit
}
