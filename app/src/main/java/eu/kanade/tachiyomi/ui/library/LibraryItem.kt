package eu.kanade.tachiyomi.ui.library

import tachiyomi.domain.library.model.LibraryManga

data class LibraryItem(
    val libraryManga: LibraryManga,
    /** Yomikae: unified entry, the title in the reading language (null = the entry's own). */
    val displayTitle: String? = null,
    val downloadCount: Int,
    val unreadCount: Long,
    val isLocal: Boolean,
    val sourceName: String,
    val sourceLanguage: String,
    val badges: Badges,
) {
    val id: Long = libraryManga.id

    data class Badges(
        val downloadCount: Int,
        val unreadCount: Long,
        val isLocal: Boolean,
        val sourceLanguage: String,
        /** Yomikae: number of entries behind a unified entry (0 = not unified). */
        val unifiedCount: Int = 0,
    )
}
