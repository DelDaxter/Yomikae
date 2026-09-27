package mihon.feature.translation.ocr

import android.content.Context
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Yomikae: fetches model files at runtime (never shipped inside the APK), verifies their
 * SHA-256, and keeps them in `filesDir/models/<group>/`. Shared by the OCR engines and by the
 * on-device LLM.
 *
 * Large files (the LLM weighs 1.8 GB) resume where they stopped: the partial `.part` file is
 * kept and the next attempt asks the server for the remaining bytes (HTTP `Range`).
 */
class ModelDownloader(private val context: Context) {

    class Spec(val fileName: String, val url: String, val sha256: String, val sizeBytes: Long)

    /** Progress callback: file name, bytes done, bytes total. */
    fun interface Progress {
        fun onProgress(fileName: String, done: Long, total: Long)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun dir(group: String): File = File(context.filesDir, "models/$group").apply { mkdirs() }

    fun file(group: String, spec: Spec): File = File(dir(group), spec.fileName)

    fun isReady(group: String, specs: List<Spec>): Boolean =
        specs.all { file(group, it).let { f -> f.exists() && f.length() == it.sizeBytes } }

    /** Bytes already on disk for the files of the group (finished or partial). */
    fun bytesPresent(group: String, specs: List<Spec>): Long = specs.sumOf { spec ->
        val done = file(group, spec)
        if (done.exists()) done.length() else File(done.path + ".part").length()
    }

    fun delete(group: String, specs: List<Spec>) {
        specs.forEach { spec ->
            file(group, spec).delete()
            File(file(group, spec).path + ".part").delete()
        }
    }

    /** Downloads what is missing; a corrupt file is deleted and the error propagated. */
    suspend fun ensure(group: String, specs: List<Spec>, progress: Progress? = null) = withIOContext {
        for (spec in specs) {
            val target = file(group, spec)
            if (target.exists() && target.length() == spec.sizeBytes) continue
            val tmp = File(target.path + ".part")
            download(spec, tmp, progress)
            val digest = sha256(tmp)
            if (!digest.equals(spec.sha256, ignoreCase = true)) {
                tmp.delete()
                logcat(LogPriority.ERROR) { "Checksum mismatch for ${spec.fileName}: $digest" }
                error("Corrupt download: ${spec.fileName}")
            }
            if (!tmp.renameTo(target)) error("Cannot store ${spec.fileName}")
        }
    }

    private fun download(spec: Spec, tmp: File, progress: Progress?) {
        var offset = if (tmp.exists()) tmp.length() else 0L
        if (offset > spec.sizeBytes) {
            tmp.delete()
            offset = 0L
        }
        logcat { "Downloading model ${spec.fileName} (${spec.sizeBytes / 1_000_000} MB, from byte $offset)" }
        val request = Request.Builder().url(spec.url).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Download failed (${response.code}) for ${spec.fileName}")
            // 206 = the server honoured the range; 200 = it did not, start over.
            if (response.code != 206) offset = 0L
            RandomAccessFile(tmp, "rw").use { out ->
                out.setLength(offset)
                out.seek(offset)
                val buffer = ByteArray(1 shl 16)
                var done = offset
                var lastReport = 0L
                response.body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (progress != null && done - lastReport >= PROGRESS_STEP) {
                            lastReport = done
                            progress.onProgress(spec.fileName, done, spec.sizeBytes)
                        }
                    }
                }
                progress?.onProgress(spec.fileName, done, spec.sizeBytes)
            }
        }
        if (tmp.length() != spec.sizeBytes) {
            error("Incomplete download for ${spec.fileName}: ${tmp.length()} of ${spec.sizeBytes} bytes")
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

    private companion object {
        const val PROGRESS_STEP = 4L * 1024 * 1024
    }
}
