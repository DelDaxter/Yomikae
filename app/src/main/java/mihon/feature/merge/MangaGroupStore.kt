package mihon.feature.merge

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Yomikae: "unified entries". One work often exists several times in the library: the raw on
 * one source, the official translation on another, a scanlation on a third. A group ties
 * those entries together under one primary entry. The primary keeps its page and its place
 * in the library; the members are hidden from the library and their chapters show up in the
 * primary's chapter list, one row per chapter number, the best version for the reading
 * language first (see [MergedChapters]).
 *
 * Nothing is copied or moved in the database: members keep their own chapters, downloads,
 * translations and reading progress, and the reader opens them directly. The group itself
 * is a small JSON file, like the translation queue.
 */
@Inject
@SingleIn(AppScope::class)
class MangaGroupStore(
    private val context: Context,
) {

    @Serializable
    data class Group(
        val id: Long,
        val primaryMangaId: Long,
        val memberIds: List<Long>,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val file: File get() = File(context.filesDir, "translations/groups.json")

    private val _groups = MutableStateFlow(load())
    val groups: StateFlow<List<Group>> = _groups

    fun groupOfPrimary(mangaId: Long): Group? = _groups.value.firstOrNull { it.primaryMangaId == mangaId }

    fun groupOfMember(mangaId: Long): Group? = _groups.value.firstOrNull { mangaId in it.memberIds }

    /** Ids of every entry that is hidden behind a primary. */
    fun memberIds(groups: List<Group> = _groups.value): Set<Long> = groups.flatMap { it.memberIds }.toSet()

    /**
     * Sets the members of the primary's group (creates it, or dissolves it when the list is
     * empty). An entry can belong to one group only: it is taken away from any other.
     */
    fun setMembers(primaryMangaId: Long, memberIds: List<Long>) {
        val members = memberIds.filter { it != primaryMangaId }.distinct()
        change { current ->
            val cleaned = current
                .map { g -> g.copy(memberIds = g.memberIds.filterNot { it in members }) }
                .filterNot { g -> g.primaryMangaId == primaryMangaId || g.primaryMangaId in members }
                .filter { g -> g.memberIds.isNotEmpty() }
            if (members.isEmpty()) {
                cleaned
            } else {
                val id = (current.maxOfOrNull { it.id } ?: 0L) + 1
                cleaned + Group(id, primaryMangaId, members)
            }
        }
    }

    /** Called when an entry leaves the library or is deleted: it leaves its group too. */
    fun forget(mangaId: Long) {
        change { current ->
            current
                .filterNot { it.primaryMangaId == mangaId }
                .map { g -> g.copy(memberIds = g.memberIds.filterNot { it == mangaId }) }
                .filter { it.memberIds.isNotEmpty() }
        }
    }

    @Synchronized
    private fun change(transform: (List<Group>) -> List<Group>) {
        _groups.update(transform)
        save(_groups.value)
    }

    private fun load(): List<Group> {
        val f = file
        if (!f.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<Group>>(f.readText()) }
            .onFailure { logcat(LogPriority.WARN, it) { "Unreadable manga groups" } }
            .getOrDefault(emptyList())
    }

    private fun save(groups: List<Group>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(groups))
            if (!tmp.renameTo(file)) error("rename failed")
        }.onFailure { logcat(LogPriority.WARN, it) { "Cannot save manga groups" } }
    }
}
