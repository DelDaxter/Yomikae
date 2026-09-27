package mihon.feature.merge

import tachiyomi.domain.chapter.model.Chapter
import java.util.Locale

/**
 * Yomikae: builds the chapter list of a unified entry. Every entry of the group brings its
 * chapters; chapters with the same number are one row, and the row shows the best version
 * for the reading language:
 *
 * 1. a chapter already in the target language (official translation, scanlation);
 * 2. a raw chapter translated on the phone;
 * 3. a raw chapter not translated yet (its row offers the translate action);
 * 4. anything else.
 *
 * A chapter keeps its own manga id, so reading, downloading and translating it go to the
 * entry it belongs to; only the display is merged.
 */
object MergedChapters {

    /** What the merge needs to know about one entry of the group. */
    class Member(
        val mangaId: Long,
        /** Language of the entry's source ("ko", "en", ...), null when unknown. */
        val language: String?,
        val sourceName: String,
        /** Added to this entry's chapter numbers before matching (editions numbered differently). */
        val numberOffset: Double = 0.0,
    )

    /**
     * The language of an entry is not always the language its source declares: aggregators
     * list raws under an English extension ("No Man's Land Raw" on WebtoonScan). An entry that
     * has a reference edition in the series memory is a raw, and so is one whose title says so.
     */
    fun entryLanguage(
        title: String,
        sourceLanguage: String?,
        hasReferenceEdition: Boolean,
        rawLanguage: String,
    ): String? = when {
        hasReferenceEdition -> rawLanguage
        RAW_TITLE.containsMatchIn(title) -> rawLanguage
        else -> sourceLanguage?.takeIf { it.isNotBlank() }
    }

    private val RAW_TITLE = Regex("""(?i)(^|[\s(\[-])raws?($|[\s)\]-])""")

    class Row(
        val chapter: Chapter,
        /** "EN · Webtoons.com", "KO · Naver Webtoon · traduit": shown next to the chapter. */
        val label: String,
        /** How many other versions of this chapter exist in the group. */
        val alternatives: Int,
    )

    fun merge(
        chaptersByManga: Map<Long, List<Chapter>>,
        members: Map<Long, Member>,
        targetLanguage: String,
        sourceLanguage: String,
        isTranslated: (chapterId: Long) -> Boolean,
        translatedLabel: String,
        /** A chapter that must stay in the list whatever its rank (the one being read). */
        preferredChapterId: Long? = null,
    ): List<Row> {
        class Candidate(val chapter: Chapter, val member: Member, val rank: Int)

        val byKey = LinkedHashMap<String, MutableList<Candidate>>()
        for ((mangaId, chapters) in chaptersByManga) {
            val member = members[mangaId] ?: continue
            for (chapter in chapters) {
                val rank = when {
                    chapter.id == preferredChapterId -> -1
                    member.language == targetLanguage -> 0
                    member.language == sourceLanguage && isTranslated(chapter.id) -> 1
                    member.language == sourceLanguage -> 2
                    else -> 3
                }
                byKey.getOrPut(key(chapter, member.numberOffset)) { ArrayList() } += Candidate(chapter, member, rank)
            }
        }

        val rows = byKey.values.map { candidates ->
            val best = candidates.minByOrNull { it.rank }!!
            val read = candidates.any { it.chapter.read }
            val label = buildString {
                append(best.member.language?.uppercase(Locale.ROOT) ?: "?")
                append(" · ")
                append(best.member.sourceName)
                if (best.rank == 1) {
                    append(" · ")
                    append(translatedLabel)
                }
            }
            Row(
                chapter = best.chapter.copy(read = read),
                label = label,
                alternatives = candidates.size - 1,
            )
        }

        // Newest first, like a source list; the source order is rewritten so Mihon's
        // "by source" sort keeps that order for the mixed list.
        return rows
            .sortedWith(
                compareByDescending<Row> {
                    it.chapter.chapterNumber
                }.thenByDescending { it.chapter.dateUpload },
            )
            .mapIndexed { index, row ->
                Row(row.chapter.copy(sourceOrder = index.toLong()), row.label, row.alternatives)
            }
    }

    /** Chapters match on their number; a chapter without a number matches on its name. */
    private fun key(chapter: Chapter, offset: Double): String =
        if (chapter.chapterNumber >= 0) {
            "n:" + String.format(Locale.ROOT, "%.2f", chapter.chapterNumber + offset)
        } else {
            "t:" + chapter.name.trim().lowercase(Locale.ROOT)
        }
}
