package eu.kanade.tachiyomi.extension.api

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.util.lang.withIOContext

@Inject
@SingleIn(AppScope::class)
class ExtensionApi(
    private val repository: ExtensionStoreRepository,
    private val updateExtensionStores: UpdateExtensionStores,
    private val extensionUpdateNotifier: ExtensionUpdateNotifier,
    private val sourcePreferences: SourcePreferences,
) {

    suspend fun findExtensions(): List<Extension.Available> {
        return withIOContext { repository.fetchExtensions() }
    }

    /**
     * @param loadedExtensions Extensions already loaded by [eu.kanade.tachiyomi.extension.ExtensionManager].
     * Only their versions are read, so there's nothing to gain from loading them a second time.
     */
    suspend fun checkForUpdates(loadedExtensions: List<Extension.Loaded>) {
        updateExtensionStores()

        val extensions = findExtensions()

        val extensionsWithUpdate = mutableListOf<Extension.Loaded>()
        for (installedExt in loadedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue
            val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
            val hasUpdatedLib = availableExt.libVersion > installedExt.libVersion
            val hasUpdate = hasUpdatedVer || hasUpdatedLib
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        if (extensionsWithUpdate.isNotEmpty() && shouldNotify()) {
            sourcePreferences.extensionUpdateNotifiedAt.set(System.currentTimeMillis())
            extensionUpdateNotifier.promptUpdates(extensionsWithUpdate.map { it.name })
        }
    }

    /** Yomikae: the user chooses how often the notification may come back (badge unaffected). */
    private fun shouldNotify(): Boolean = when (sourcePreferences.extensionUpdateNotifications.get()) {
        SourcePreferences.EXT_NOTIFY_NEVER -> false
        SourcePreferences.EXT_NOTIFY_DAILY ->
            System.currentTimeMillis() - sourcePreferences.extensionUpdateNotifiedAt.get() >= DAY_MS
        else -> true
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
