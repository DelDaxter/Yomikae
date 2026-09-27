package mihon.feature.translation.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil

/**
 * Yomikae: PaddleOCR PP-OCRv5 (mobile text detector + Korean recognizer) through ONNX Runtime.
 *
 * Detection: DBNet gives a probability map the size of the (resized) image; [DbPostprocessor]
 * turns it into text-line quads. Recognition: each quad is warped to a horizontal strip
 * 48 px high and decoded with CTC over the Korean dictionary (Korean + English + symbols).
 *
 * Models (Apache-2.0, PaddlePaddle on Hugging Face) are downloaded on first use, about 18 MB.
 * Preprocessing follows PaddleOCR: BGR order, ImageNet mean/std for detection, 0.5/0.5 for
 * recognition. Based on ciddwd/overlay-translator (Apache-2.0) for the ONNX details.
 */
class PaddleOcr(
    private val context: Context,
    private val language: String,
) : OcrEngine {

    private val downloader = ModelDownloader(context)
    private var env: OrtEnvironment? = null
    private var det: OrtSession? = null
    private var rec: OrtSession? = null
    private var keys: List<String> = emptyList()

    override suspend fun prepare() {
        val specs = specsFor(language)
        downloader.ensure(GROUP, specs)
        withIOContext {
            val dir = downloader.dir(GROUP)
            val e = OrtEnvironment.getEnvironment().also { env = it }
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            }
            det = e.createSession(File(dir, DET_FILE).absolutePath, options)
            rec = e.createSession(File(dir, recFileFor(language)).absolutePath, options)
            keys = File(dir, dictFileFor(language)).readLines()
                .map { it.trim('\r', '\n', ' ', '\t') }
                .filter { it.isNotEmpty() }
            logcat { "PaddleOCR ready: ${keys.size} keys" }
        }
    }

    override suspend fun recognize(bitmap: Bitmap): List<OcrBlock> = withIOContext {
        val quads = detect(bitmap)
        quads.mapNotNull { quad ->
            val text = recognizeQuad(bitmap, quad) ?: return@mapNotNull null
            if (text.isBlank()) return@mapNotNull null
            val box = Rect(
                quad.minX.toInt().coerceIn(0, bitmap.width),
                quad.minY.toInt().coerceIn(0, bitmap.height),
                quad.maxX.toInt().coerceIn(0, bitmap.width),
                quad.maxY.toInt().coerceIn(0, bitmap.height),
            )
            OcrBlock(text, box, 1)
        }
    }

    // ---- detection ----

    private fun detect(bitmap: Bitmap): List<DbPostprocessor.Quad> {
        val session = det ?: return emptyList()
        val e = env ?: return emptyList()
        // Sides multiple of 32, longest side capped, to keep the tensor reasonable on a phone.
        val scale = minOf(1f, DET_MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val w = (Math.round(bitmap.width * scale / 32f) * 32).coerceAtLeast(32)
        val h = (Math.round(bitmap.height * scale / 32f) * 32).coerceAtLeast(32)
        val resized = if (w == bitmap.width &&
            h == bitmap.height
        ) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
        try {
            val input = toNchw(resized, DET_MEAN, DET_STD)
            OnnxTensor.createTensor(
                e,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, h.toLong(), w.toLong()),
            ).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val out = result.get(0).value as Array<Array<Array<FloatArray>>>
                    val probMap = out[0][0]
                    val scaleX = bitmap.width.toFloat() / probMap[0].size
                    val scaleY = bitmap.height.toFloat() / probMap.size
                    return DbPostprocessor.extractQuads(
                        probMap,
                        scaleX,
                        scaleY,
                        binThresh = DET_THRESH,
                        scoreThresh = DET_BOX_THRESH,
                        unclipRatio = DET_UNCLIP,
                    )
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "PaddleOCR detection failed" }
            return emptyList()
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    // ---- recognition ----

    private fun recognizeQuad(src: Bitmap, quad: DbPostprocessor.Quad): String? {
        val session = rec ?: return null
        val e = env ?: return null
        val crop = warp(src, quad) ?: return null
        try {
            val targetW = ceil(
                crop.width * REC_HEIGHT.toDouble() / crop.height,
            ).toInt().coerceIn(REC_MIN_WIDTH, REC_MAX_WIDTH)
            val tensorW = targetW.coerceAtLeast(REC_BASE_WIDTH)
            val resized = Bitmap.createScaledBitmap(crop, targetW, REC_HEIGHT, true)
            try {
                val input = toNchwPadded(resized, tensorW, REC_MEAN, REC_STD)
                OnnxTensor.createTensor(
                    e,
                    FloatBuffer.wrap(input),
                    longArrayOf(1, 3, REC_HEIGHT.toLong(), tensorW.toLong()),
                ).use { tensor ->
                    session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val out = result.get(0).value as Array<Array<FloatArray>>
                        val (text, confidence) = ctcDecode(out[0])
                        // PaddleOCR's drop_score: a line the model is unsure about is usually
                        // not text at all (sound effects drawn as art, texture, logos).
                        if (confidence < REC_DROP_SCORE) {
                            logcat(LogPriority.DEBUG) { "PaddleOCR dropped \"$text\" (confidence $confidence)" }
                            return null
                        }
                        return text
                    }
                }
            } finally {
                resized.recycle()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "PaddleOCR recognition failed" }
            return null
        } finally {
            crop.recycle()
        }
    }

    /** Perspective-corrects the quad into a horizontal rectangle (cv2.warpPerspective equivalent). */
    private fun warp(src: Bitmap, quad: DbPostprocessor.Quad): Bitmap? {
        val cropW = maxOf(quad.width, 2f).toInt()
        val cropH = maxOf(quad.height, 2f).toInt()
        if (cropW > 4096 || cropH > 4096) return null
        val matrix = Matrix()
        val ok = matrix.setPolyToPoly(
            floatArrayOf(quad.p0.x, quad.p0.y, quad.p1.x, quad.p1.y, quad.p2.x, quad.p2.y, quad.p3.x, quad.p3.y),
            0,
            floatArrayOf(0f, 0f, cropW.toFloat(), 0f, cropW.toFloat(), cropH.toFloat(), 0f, cropH.toFloat()),
            0,
            4,
        )
        if (!ok) return null
        val out = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, matrix, WARP_PAINT)
        return out
    }

    /**
     * Greedy CTC: argmax per step, drop repeats and blanks (index 0); last index = space.
     * Returns the text and the mean probability of the kept characters (the model's softmax).
     */
    private fun ctcDecode(logits: Array<FloatArray>): Pair<String, Float> {
        val sb = StringBuilder()
        var prev = -1
        var confidenceSum = 0f
        var kept = 0
        for (step in logits) {
            var best = 0
            var bestVal = step[0]
            for (i in 1 until step.size) {
                if (step[i] > bestVal) {
                    bestVal = step[i]
                    best = i
                }
            }
            if (best != 0 && best != prev) {
                val k = best - 1
                when {
                    k in keys.indices -> sb.append(keys[k])
                    k == keys.size -> sb.append(' ')
                }
                confidenceSum += bestVal
                kept++
            }
            prev = best
        }
        val confidence = if (kept == 0) 0f else confidenceSum / kept
        return sb.toString().trim() to confidence
    }

    // ---- tensors (BGR, CHW) ----

    private fun toNchw(bitmap: Bitmap, mean: FloatArray, std: FloatArray): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val plane = w * h
        val arr = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            arr[i] = (b - mean[0]) / std[0]
            arr[plane + i] = (g - mean[1]) / std[1]
            arr[2 * plane + i] = (r - mean[2]) / std[2]
        }
        return arr
    }

    private fun toNchwPadded(bitmap: Bitmap, paddedWidth: Int, mean: FloatArray, std: FloatArray): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val plane = paddedWidth * h
        val arr = FloatArray(3 * plane) // zeros = neutral padding for mean/std 0.5
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                val pos = y * paddedWidth + x
                arr[pos] = (b - mean[0]) / std[0]
                arr[plane + pos] = (g - mean[1]) / std[1]
                arr[2 * plane + pos] = (r - mean[2]) / std[2]
            }
        }
        return arr
    }

    override fun close() {
        rec?.close()
        det?.close()
        rec = null
        det = null
    }

    companion object {
        const val GROUP = "paddleocr"
        private const val DET_FILE = "PP-OCRv5_mobile_det.onnx"
        private const val DET_MAX_SIDE = 1600
        private const val DET_THRESH = 0.3f
        private const val DET_BOX_THRESH = 0.6f
        private const val DET_UNCLIP = 1.6f
        private const val REC_HEIGHT = 48

        /** PaddleOCR's default `drop_score`: lines read with a lower mean probability are ignored. */
        private const val REC_DROP_SCORE = 0.5f
        private const val REC_MIN_WIDTH = 8
        private const val REC_BASE_WIDTH = 320
        private const val REC_MAX_WIDTH = 2048
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val WARP_PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        fun recFileFor(language: String) = "${language}_PP-OCRv5_mobile_rec.onnx"
        fun dictFileFor(language: String) = "ppocrv5_${language}_dict.txt"

        /** Languages with a ready PP-OCRv5 recognizer here. */
        val SUPPORTED_LANGUAGES = setOf("ko")

        fun specsFor(language: String): List<ModelDownloader.Spec> = listOf(
            ModelDownloader.Spec(
                DET_FILE,
                "https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx/resolve/main/inference.onnx",
                "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d",
                4_826_518,
            ),
            ModelDownloader.Spec(
                recFileFor("ko"),
                "https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.onnx",
                "92f0b7785e64fc9090106a241cf4c1eb97472824558272751b88a2a4476d3a08",
                13_418_787,
            ),
            ModelDownloader.Spec(
                dictFileFor("ko"),
                "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils/dict/ppocrv5_korean_dict.txt",
                "a88071c68c01707489baa79ebe0405b7beb5cca229f4fc94cc3ef992328802d7",
                47_451,
            ),
        )
    }
}
