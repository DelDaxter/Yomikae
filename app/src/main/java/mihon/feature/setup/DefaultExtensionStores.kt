package mihon.feature.setup

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.flow.first
import logcat.LogPriority
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.extension.interactor.GetExtensionStores
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat

/**
 * Yomikae: registers the extension stores a new user needs, once, at first launch, so the
 * setup is "install the APK, pick the sources you want" with no address to type. The stores
 * can still be removed or completed in Settings › Browse › Extension stores.
 */
@Inject
@SingleIn(AppScope::class)
class DefaultExtensionStores(
    preferenceStore: PreferenceStore,
    private val getExtensionStores: GetExtensionStores,
    private val addExtensionStore: AddExtensionStore,
    private val extensionManager: ExtensionManager,
) {

    private val done = preferenceStore.getBoolean("default_extension_stores_added", false)

    /** Adds the missing default stores; retried at the next launch while one fails (offline). */
    suspend fun seedOnce() {
        if (done.get()) return
        val existing = getExtensionStores.subscribe().first().map { it.indexUrl }.toSet()
        var allAdded = true
        for (url in DEFAULT_STORES) {
            if (url in existing) continue
            addExtensionStore(url).onFailure {
                allAdded = false
                logcat(LogPriority.WARN, it) { "Default extension store not added yet: $url" }
            }
        }
        if (allAdded) {
            done.set(true)
            runCatching { extensionManager.findAvailableExtensions() }
        }
    }

    companion object {
        val DEFAULT_STORES = listOf(
            // Keiyoushi: the community's English and multilingual sources (Webtoons.com, ...).
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
            // Korean sources (Naver Webtoon, ...), for the raws.
            "https://raw.githubusercontent.com/oneulddu/Korean-Mihon-Extensions/repo/index.min.json",
        )
    }
}
