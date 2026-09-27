package mihon.feature.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.serialization.Serializable
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.io.Closeable

/**
 * Yomikae: translates one page image.
 *
 * Pipeline for one page:
 *   1. decode the image,
 *   2. OCR with the ML Kit recognizer of the source language (tiled when the page is very tall),
 *   3. hand every text block of the page to the [TextTranslator] (ML Kit, LLM, ...),
 *   4. paint the translations over the original text (see [PageRenderer]),
 *   5. encode as JPEG.
 *
 * One instance is meant to live for the duration of a job. Call [close] when done.
 */
class PageTranslator(
    sourceLanguage: String,
    private val translator: TextTranslator,
    /** False = read the text only (sidecars), do not paint any page. */
    private val renderPages: Boolean = true,
) : Closeable {

    private val recognizer: TextRecognizer = when (sourceLanguage) {
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val renderer = PageRenderer()

    /** Prepares the translation engine (model download, connection check). Call once. */
    suspend fun prepare() {
        translator.prepare()
    }

    /** One text block of a page with its translation, in page pixel coordinates. */
    @Serializable
    data class TranslatedBlock(
        val source: String,
        val target: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    /** What a page gave: the rendered image (null when nothing to translate) and the text pairs. */
    class Result(val jpeg: ByteArray?, val width: Int, val height: Int, val blocks: List<TranslatedBlock>)

    /**
     * Returns the translated page (JPEG bytes plus the source/target text pairs), or null when
     * the page could not be decoded. The image is null when the page has no text worth
     * translating (the caller then keeps the original).
     */
    suspend fun translatePage(imageBytes: ByteArray): Result? {
        val bitmap = decode(imageBytes) ?: return null
        try {
            // Drop watermarks and stray marks, then glue the pieces of one bubble back together:
            // ML Kit often splits a bubble into two blocks, which breaks both the context given
            // to the translator and the rendering (two text sizes in one bubble).
            val blocks = mergeBubbleBlocks(recognize(bitmap).filterNot { isNoise(it.text) })
            if (blocks.isEmpty()) return Result(null, bitmap.width, bitmap.height, emptyList())

            // The whole page goes to the engine at once, so context-aware engines can use it.
            val texts = translator.translate(blocks.map { it.text })
            val pairs = blocks.zip(texts) { block, text ->
                TranslatedBlock(block.text, text, block.box.left, block.box.top, block.box.right, block.box.bottom)
            }
            val translated = blocks.zip(texts) { block, text ->
                PageRenderer.Block(
                    box = block.box,
                    lineCount = block.lineCount,
                    text = text,
                )
            }.filter { it.text.isNotBlank() }
            if (translated.isEmpty() || !renderPages) return Result(null, bitmap.width, bitmap.height, pairs)

            val output = renderer.render(bitmap, translated)
            val jpeg = ByteArrayOutputStream().use { stream ->
                output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                if (output !== bitmap) output.recycle()
                stream.toByteArray()
            }
            return Result(jpeg, bitmap.width, bitmap.height, pairs)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * ML Kit shrinks very large images before reading them, which makes small dialogue text
     * unreadable on long webtoon strips. So tall pages are cut into overlapping horizontal
     * bands, each band is read separately, and every block is kept only from the band where its
     * centre is far from the cut.
     */
    private suspend fun recognize(bitmap: Bitmap): List<OcrBlock> {
        logcat { "Translation: OCR on ${bitmap.width}x${bitmap.height}" }
        if (bitmap.height <= TILE_HEIGHT) {
            return recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().toOcrBlocks(0)
        }

        val result = ArrayList<OcrBlock>()
        var top = 0
        while (top < bitmap.height) {
            val height = minOf(TILE_HEIGHT, bitmap.height - top)
            val tile = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
            val blocks = try {
                recognizer.process(InputImage.fromBitmap(tile, 0)).await().toOcrBlocks(top)
            } finally {
                tile.recycle()
            }

            val isFirst = top == 0
            val isLast = top + height >= bitmap.height
            val keepFrom = if (isFirst) 0 else TILE_OVERLAP / 2
            val keepTo = if (isLast) height else height - TILE_OVERLAP / 2
            for (block in blocks) {
                // The box is already in page coordinates, so compare inside the band.
                if (block.box.centerY() - top in keepFrom until keepTo) {
                    result += block
                }
            }

            if (isLast) break
            top += TILE_HEIGHT - TILE_OVERLAP
        }
        return result
    }

    private fun decode(imageBytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        logcat { "Translation: bounds ${bounds.outWidth}x${bounds.outHeight}" }
        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        if (pixels <= 0) return null
        if (pixels > MAX_PIXELS) {
            logcat(LogPriority.WARN) { "Page too large to translate: ${bounds.outWidth}x${bounds.outHeight}" }
            return null
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
        logcat { "Translation: decoded ${bitmap?.width}x${bitmap?.height}" }
        return bitmap
    }

    override fun close() {
        recognizer.close()
        translator.close()
    }

    /** A text block in page coordinates: the text (lines joined), its box and its line count. */
    private class OcrBlock(val text: String, val box: Rect, val lineCount: Int)

    /** Watermarks (site names), lone symbols and digits are not dialogue. */
    private fun isNoise(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2) return true
        if (URL_PATTERN.containsMatchIn(t)) return true
        if (WATERMARK_WORDS.any { t.contains(it) }) return true
        return !t.any { it.isLetter() }
    }

    /**
     * Merges blocks that sit right on top of each other and overlap horizontally: with speech
     * bubbles that is almost always the same bubble read in two pieces.
     */
    private fun mergeBubbleBlocks(blocks: List<OcrBlock>): List<OcrBlock> {
        val sorted = blocks.sortedBy { it.box.top }
        val merged = ArrayList<OcrBlock>()
        for (block in sorted) {
            val index = merged.indexOfFirst { belongsTogether(it, block) }
            if (index < 0) {
                merged += block
            } else {
                val a = merged[index]
                val box = Rect(a.box).apply { union(block.box) }
                // Two reads of the same text (overlapping OCR boxes) must not be glued twice.
                val text = when {
                    a.text.contains(block.text) -> a.text
                    block.text.contains(a.text) -> block.text
                    else -> a.text + " " + block.text
                }
                merged[index] = OcrBlock(text, box, a.lineCount + block.lineCount)
            }
        }
        return merged
    }

    private fun belongsTogether(a: OcrBlock, b: OcrBlock): Boolean {
        val lineHeight = maxOf(a.box.height() / a.lineCount, b.box.height() / b.lineCount, 1)
        val verticalGap = maxOf(b.box.top - a.box.bottom, a.box.top - b.box.bottom)
        if (verticalGap > lineHeight * MERGE_MAX_GAP_LINES) return false
        val overlap = minOf(a.box.right, b.box.right) - maxOf(a.box.left, b.box.left)
        val narrower = minOf(a.box.width(), b.box.width()).coerceAtLeast(1)
        return overlap.toFloat() / narrower >= MERGE_MIN_OVERLAP
    }

    /** Converts ML Kit's result into [OcrBlock]s, shifting boxes down by [dy] (the band's top). */
    private fun Text.toOcrBlocks(dy: Int): List<OcrBlock> = textBlocks.mapNotNull { block ->
        val box = block.boundingBox ?: return@mapNotNull null
        val text = block.lines.joinToString(" ") { it.text }.trim()
        if (text.isBlank()) return@mapNotNull null
        OcrBlock(text, Rect(box).apply { offset(0, dy) }, block.lines.size.coerceAtLeast(1))
    }

    private companion object {
        const val TILE_HEIGHT = 2048
        const val TILE_OVERLAP = 256
        const val MAX_PIXELS = 60_000_000L
        const val JPEG_QUALITY = 90
        const val MERGE_MAX_GAP_LINES = 0.8f
        const val MERGE_MIN_OVERLAP = 0.4f
        val URL_PATTERN = Regex("""(?i)(\.com|\.net|\.org|\.kr|\.io|www\.|http)""")

        /** Site logos and "read it first on..." banners that scan sites stamp on pages. */
        val WATERMARK_WORDS = listOf("뉴토끼", "구글검색", "웹툰미리보기", "웹튼미리보기", "짬툰", "마나토끼", "북토끼", "툰코", "Newtoki", "Toonkor")
    }
}
