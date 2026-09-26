package mihon.feature.translation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Yomikae: in-memory view of the translation queue, shared between the background job
 * (which reports progress) and the UI (chapter rows, queue screen).
 *
 * WorkManager remains the source of truth for what will run; this class only mirrors it so
 * the UI can show "queued / translating page 12 of 43 / done / failed" and estimate the time
 * left. It lives as long as the process does; after a process restart the job re-registers
 * the chapters it is working on.
 */
@Inject
@SingleIn(AppScope::class)
class TranslationQueue {

    enum class Status { PENDING, RUNNING, DONE, ERROR, CANCELLED }

    data class Item(
        val chapterId: Long,
        val mangaId: Long,
        val mangaTitle: String,
        val chapterName: String,
        val status: Status = Status.PENDING,
        val page: Int = 0,
        val pageCount: Int = 0,
        val error: String? = null,
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

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    /** Called when the user asks for chapters; they show as pending right away. */
    fun enqueue(newItems: List<Item>) {
        _items.update { current ->
            val newIds = newItems.map { it.chapterId }.toSet()
            // A chapter asked for again goes back to pending (its old entry is dropped).
            current.filterNot { it.chapterId in newIds && it.status != Status.RUNNING } +
                newItems.filterNot { new ->
                    current.any { it.chapterId == new.chapterId && it.status == Status.RUNNING }
                }
        }
    }

    fun markRunning(chapterId: Long, pageCount: Int) {
        updateItem(chapterId) { it.copy(status = Status.RUNNING, page = 0, pageCount = pageCount, error = null) }
    }

    fun markPage(chapterId: Long, page: Int, pageMillis: Long?) {
        updateItem(chapterId) { it.copy(page = page) }
        if (pageMillis != null) {
            _stats.update { it.copy(pagesDone = it.pagesDone + 1, totalMillis = it.totalMillis + pageMillis) }
        }
    }

    fun markDone(chapterId: Long) {
        val item = _items.value.firstOrNull { it.chapterId == chapterId }
        updateItem(chapterId) { it.copy(status = Status.DONE, page = it.pageCount) }
        if (item != null && item.pageCount > 0) {
            _stats.update {
                it.copy(chaptersDone = it.chaptersDone + 1, chapterPages = it.chapterPages + item.pageCount)
            }
        }
    }

    fun markError(chapterId: Long, message: String?) {
        updateItem(chapterId) { it.copy(status = Status.ERROR, error = message) }
    }

    /** Everything not finished becomes cancelled (the job itself is stopped by WorkManager). */
    fun cancelAll() {
        _items.update { list ->
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
        _items.update { list -> list.filter { it.status == Status.PENDING || it.status == Status.RUNNING } }
    }

    fun statusOf(chapterId: Long): Status? = _items.value.firstOrNull { it.chapterId == chapterId }?.status

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
                Status.PENDING -> if (item.pageCount >
                    0
                ) {
                    item.pageCount.toDouble()
                } else {
                    _stats.value.averagePagesPerChapter
                }
                else -> 0.0
            }
        }
        return (pagesLeft * perPage).toLong()
    }

    private fun updateItem(chapterId: Long, transform: (Item) -> Item) {
        _items.update { list -> list.map { if (it.chapterId == chapterId) transform(it) else it } }
    }

    private companion object {
        const val DEFAULT_PAGES_PER_CHAPTER = 40.0
    }
}
