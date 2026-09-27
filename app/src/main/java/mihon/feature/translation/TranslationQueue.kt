package mihon.feature.translation

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
 * Yomikae: the translation queue, source of truth for what gets translated and in which
 * order (the WorkManager job only pulls the next pending item from here).
 *
 * The order can be changed by the user (move up/down/top/bottom, "translate now", remove),
 * like Mihon's download queue. The queue is saved to disk, so it survives a process restart.
 */
@Inject
@SingleIn(AppScope::class)
class TranslationQueue(
    private val context: Context,
) {

    enum class Status { PENDING, RUNNING, DONE, ERROR, CANCELLED }

    @Serializable
    data class Item(
        val chapterId: Long,
        val mangaId: Long,
        val mangaTitle: String,
        val chapterName: String,
        val mode: String = ChapterTranslationJob.MODE_TRANSLATE,
        val status: Status = Status.PENDING,
        val page: Int = 0,
        val pageCount: Int = 0,
        val error: String? = null,
        /** Set by "translate now": the running chapter yields after its current page. */
        val urgent: Boolean = false,
    )

    /** Timing statistics used for the "time left" estimate. */
    data class Stats(
        val pagesDone: Long = 0,
        val totalMillis: Long = 0,
        val chaptersDone: Long = 0,
        val chapterPages: Long = 0,
    ) {
        val averageMillisPerPage: Double?
            get() = if (pagesDone > 0) totalMillis.toDouble() / pagesDone else null

        val averagePagesPerChapter: Double
            get() = if (chaptersDone > 0) chapterPages.toDouble() / chaptersDone else DEFAULT_PAGES_PER_CHAPTER
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val file: File get() = File(context.filesDir, "translations/queue.json")

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Item>> = _items

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    // ---- what the user does ----

    /** Appends chapters; a chapter already waiting or running is left where it is. */
    fun enqueue(newItems: List<Item>) {
        change { current ->
            val active = current.filter {
                it.status == Status.PENDING || it.status == Status.RUNNING
            }.map { it.chapterId }.toSet()
            val finishedIds = newItems.map { it.chapterId }.toSet()
            // A finished chapter asked for again goes back to pending at the end.
            current.filterNot { it.chapterId in finishedIds && it.status !in listOf(Status.PENDING, Status.RUNNING) } +
                newItems.filterNot { it.chapterId in active }
        }
    }

    /** "Translate now": to the front of the pending items, and the running chapter will yield. */
    fun startNow(chapterId: Long) {
        change { current ->
            val item =
                current.firstOrNull { it.chapterId == chapterId && it.status == Status.PENDING }
                    ?: return@change current
            val rest = current.filterNot { it.chapterId == chapterId }
            val head = rest.takeWhile { it.status == Status.RUNNING }
            head + item.copy(status = Status.PENDING, urgent = true) + rest.drop(head.size)
        }
    }

    fun moveUp(chapterId: Long) = move(chapterId, -1)
    fun moveDown(chapterId: Long) = move(chapterId, +1)

    fun moveToTop(chapterId: Long) {
        change { current ->
            val item =
                current.firstOrNull { it.chapterId == chapterId && it.status == Status.PENDING }
                    ?: return@change current
            val rest = current.filterNot { it.chapterId == chapterId }
            val head = rest.takeWhile { it.status == Status.RUNNING }
            head + item + rest.drop(head.size)
        }
    }

    fun moveToBottom(chapterId: Long) {
        change { current ->
            val item =
                current.firstOrNull { it.chapterId == chapterId && it.status == Status.PENDING }
                    ?: return@change current
            current.filterNot { it.chapterId == chapterId } + item
        }
    }

    /** Removes a waiting chapter; a running one is marked cancelled (the job checks it). */
    fun remove(chapterId: Long) {
        change { current ->
            current.mapNotNull {
                when {
                    it.chapterId != chapterId -> it
                    it.status == Status.RUNNING -> it.copy(status = Status.CANCELLED)
                    else -> null
                }
            }
        }
    }

    /** Everything not finished becomes cancelled. */
    fun cancelAll() {
        change { list ->
            list.map {
                if (it.status == Status.PENDING ||
                    it.status == Status.RUNNING
                ) {
                    it.copy(status = Status.CANCELLED)
                } else {
                    it
                }
            }
        }
    }

    fun clearFinished() {
        change { list -> list.filter { it.status == Status.PENDING || it.status == Status.RUNNING } }
    }

    // ---- what the job does ----

    /** Next chapter to work on: an urgent one first, otherwise the first pending in order. */
    fun nextPending(): Item? = _items.value.let { list ->
        list.firstOrNull { it.status == Status.PENDING && it.urgent }
            ?: list.firstOrNull { it.status == Status.PENDING }
    }

    /** True when the running chapter should stop after its current page. */
    fun shouldYield(runningChapterId: Long): Boolean = _items.value.any {
        it.chapterId != runningChapterId && it.status == Status.PENDING && it.urgent
    } || _items.value.any { it.chapterId == runningChapterId && it.status == Status.CANCELLED }

    fun isCancelled(chapterId: Long): Boolean = _items.value.any {
        it.chapterId == chapterId &&
            it.status == Status.CANCELLED
    }

    fun markRunning(chapterId: Long, pageCount: Int) {
        updateItem(chapterId) { it.copy(status = Status.RUNNING, pageCount = pageCount, error = null, urgent = false) }
    }

    fun markPage(chapterId: Long, page: Int, pageMillis: Long?) {
        updateItem(chapterId) { it.copy(page = page) }
        if (pageMillis != null) {
            _stats.update { it.copy(pagesDone = it.pagesDone + 1, totalMillis = it.totalMillis + pageMillis) }
        }
    }

    /** The chapter was interrupted (yield or restart): back to pending, progress kept. */
    fun markPending(chapterId: Long) {
        updateItem(chapterId) { if (it.status == Status.RUNNING) it.copy(status = Status.PENDING) else it }
    }

    fun markDone(chapterId: Long) {
        val item = _items.value.firstOrNull { it.chapterId == chapterId }
        updateItem(chapterId) { it.copy(status = Status.DONE, page = it.pageCount, urgent = false) }
        if (item != null && item.pageCount > 0) {
            _stats.update {
                it.copy(chaptersDone = it.chaptersDone + 1, chapterPages = it.chapterPages + item.pageCount)
            }
        }
    }

    fun markError(chapterId: Long, message: String?) {
        updateItem(chapterId) { it.copy(status = Status.ERROR, error = message, urgent = false) }
    }

    /** After a process restart nothing can still be running. */
    fun recoverAfterRestart() {
        change { list -> list.map { if (it.status == Status.RUNNING) it.copy(status = Status.PENDING) else it } }
    }

    fun statusOf(chapterId: Long): Status? = _items.value.firstOrNull { it.chapterId == chapterId }?.status

    fun hasPending(): Boolean = _items.value.any { it.status == Status.PENDING }

    /**
     * Rough time left in milliseconds, or null when nothing is known yet. Uses the measured
     * average per page; pending chapters whose page count is unknown are assumed to be as
     * long as the chapters already done.
     */
    fun estimatedRemainingMillis(): Long? {
        val perPage = _stats.value.averageMillisPerPage ?: return null
        val pagesLeft = _items.value.sumOf { item ->
            when (item.status) {
                Status.RUNNING -> (item.pageCount - item.page).coerceAtLeast(0).toDouble()
                Status.PENDING -> if (item.pageCount > 0) {
                    (item.pageCount - item.page).coerceAtLeast(0).toDouble()
                } else {
                    _stats.value.averagePagesPerChapter
                }
                else -> 0.0
            }
        }
        return (pagesLeft * perPage).toLong()
    }

    // ---- internals ----

    private fun move(chapterId: Long, delta: Int) {
        change { current ->
            val list = current.toMutableList()
            val index = list.indexOfFirst { it.chapterId == chapterId && it.status == Status.PENDING }
            if (index < 0) return@change current
            val target = (index + delta).coerceIn(0, list.size - 1)
            if (target == index || list[target].status == Status.RUNNING) return@change current
            val item = list.removeAt(index)
            list.add(target, item)
            list
        }
    }

    private fun updateItem(chapterId: Long, transform: (Item) -> Item) {
        change { list -> list.map { if (it.chapterId == chapterId) transform(it) else it } }
    }

    /**
     * Every change goes through here, under one lock: the UI thread (reorder), the worker
     * (progress) and the download manager (delete) all touch the queue, and the file must
     * always hold the latest state in full.
     */
    @Synchronized
    private fun change(transform: (List<Item>) -> List<Item>) {
        _items.update(transform)
        save(_items.value)
    }

    private fun load(): List<Item> {
        val f = file
        if (!f.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<Item>>(f.readText()) }
            .onFailure { logcat(LogPriority.WARN, it) { "Unreadable translation queue" } }
            .getOrDefault(emptyList())
            .map { if (it.status == Status.RUNNING) it.copy(status = Status.PENDING) else it }
    }

    private fun save(items: List<Item>) {
        runCatching {
            file.parentFile?.mkdirs()
            // Write then rename: a crash mid-write leaves the previous file intact.
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(items))
            if (!tmp.renameTo(file)) error("rename failed")
        }.onFailure { logcat(LogPriority.WARN, it) { "Cannot save translation queue" } }
    }

    private companion object {
        const val DEFAULT_PAGES_PER_CHAPTER = 40.0
    }
}
