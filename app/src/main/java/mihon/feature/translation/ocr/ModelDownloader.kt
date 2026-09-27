package mihon.feature.translation.ocr

import android.content.Context
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Yomikae: fetches model files at runtime (never shipped inside the APK), verifies their
 * SHA-256, and keeps them in `filesDir/models/<group>/`. Shared by the OCR engines and, later,
 * by the on-device LLM.
 */
class ModelDownloader(private val context: Context) {

    class Spec(val fileName: String, val url: String, val sha256: String, val sizeBytes: Long)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun dir(group: String): File = File(context.filesDir, "models/$group").apply { mkdirs() }

    fun isReady(group: String, specs: List<Spec>): Boolean =
        specs.all { File(dir(group), it.fileName).let { f -> f.exists() && f.length() == it.sizeBytes } }

    /** Downloads what is missing; a corrupt file is deleted and the error propagated. */
    suspend fun ensure(group: String, specs: List<Spec>, onProgress: ((String) -> Unit)? = null) = withIOContext {
        for (spec in specs) {
            val target = File(dir(group), spec.fileName)
            if (target.exists() && target.length() == spec.sizeBytes) continue
            onProgress?.invoke(spec.fileName)
            logcat { "Downloading model ${spec.fileName} (${spec.sizeBytes / 1_000_000} MB)" }
            val tmp = File(target.path + ".part")
            val request = Request.Builder().url(spec.url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download failed (${response.code}) for ${spec.fileName}")
                tmp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            }
            val digest = sha256(tmp)
            if (!digest.equals(spec.sha256, ignoreCase = true)) {
                tmp.delete()
                logcat(LogPriority.ERROR) { "Checksum mismatch for ${spec.fileName}: $digest" }
                error("Corrupt download: ${spec.fileName}")
            }
            if (!tmp.renameTo(target)) error("Cannot store ${spec.fileName}")
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
