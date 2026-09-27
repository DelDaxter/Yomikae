package mihon.feature.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import kotlinx.serialization.Serializable
import logcat.LogPriority
import mihon.feature.translation.ocr.OcrBlock
import mihon.feature.translation.ocr.OcrEngine
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
    private val ocr: OcrEngine,
    private val translator: TextTranslator,
    /** Language the pages are supposed to be in; blocks without a single character of it are dropped. */
    private val sourceLanguage: String,
    /** False = read the text only (sidecars), do not paint any page. */
    private val renderPages: Boolean = true,
) : Closeable {

    private val renderer = PageRenderer()

    /** Prepares both engines (model downloads, sessions, connection check). Call once. */
    suspend fun prepare() {
        ocr.prepare()
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
            val t0 = System.currentTimeMillis()
            val blocks = mergeBubbleBlocks(
                recognize(bitmap).filterNot { isNoise(it.text) || isCornerStamp(it, bitmap.width, bitmap.height) },
            )
            val ocrMillis = System.currentTimeMillis() - t0
            if (blocks.isEmpty()) {
                logcat { "Translation: timings ocr=${ocrMillis}ms, no text" }
                return Result(null, bitmap.width, bitmap.height, emptyList())
            }

            // The whole page goes to the engine at once, so context-aware engines can use it.
            val t1 = System.currentTimeMillis()
            val texts = translator.translate(blocks.map { it.text })
            val llmMillis = System.currentTimeMillis() - t1
            logcat { "Translation: timings ocr=${ocrMillis}ms translate=${llmMillis}ms for ${blocks.size} blocks" }
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
            return ocr.recognize(bitmap)
        }

        val result = ArrayList<OcrBlock>()
        var top = 0
        while (top < bitmap.height) {
            val height = minOf(TILE_HEIGHT, bitmap.height - top)
            val tile = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
            val blocks = try {
                ocr.recognize(tile).map { OcrBlock(it.text, Rect(it.box).apply { offset(0, top) }, it.lineCount) }
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
        ocr.close()
        translator.close()
    }

    /** Watermarks (site names), lone symbols and digits are not dialogue. */
    private fun isNoise(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2) return true
        if (URL_PATTERN.containsMatchIn(t)) return true
        // Site banners are compared without spaces: OCR splits "웹툰미리보기" at random places.
        val compact = t.filterNot { it.isWhitespace() }
        if (WATERMARK_WORDS.any { compact.contains(it) }) return true
        if (WATERMARK_PARTS.count { compact.contains(it) } >= 2) return true
        if (t.length <= 8 && WATERMARK_WORDS.any { jamoSimilarity(t, it) >= 0.6f }) return true
        if (!t.any { it.isLetter() }) return true
        // A Korean page never yields a block without Hangul; such a block is a misread of
        // artwork, a logo, or a page that is not in the source language at all.
        return !hasSourceScript(t)
    }

    /**
     * Site stamps are short, glued to the left or right edge of the page and near its top or
     * bottom (measured on webtoon pages: right edge at 97-99 % of the width, width about 20 %).
     * Dialogue in a bottom corner keeps a margin from the edge, so the edge test is what
     * separates the two, not the centre of the box. A looser test applies when the text also
     * sounds like a known site name ("짬툰" read "잡둔").
     */
    private fun isCornerStamp(block: OcrBlock, pageWidth: Int, pageHeight: Int): Boolean {
        val t = block.text.trim()
        if (t.length > 8 || block.box.width() >= pageWidth * 0.3f) return false
        val cy = block.box.centerY().toFloat() / pageHeight
        val gluedToEdge = block.box.right > pageWidth * 0.93f || block.box.left < pageWidth * 0.07f
        val outerBand = cy < 0.15f || cy > 0.85f
        if (gluedToEdge && outerBand) return true
        // Pages cut into squares put the stamp anywhere in the lower half, so for a text that
        // sounds like a site name or ends with a latin fragment ("찜dom", "gom", "잠둔com") the
        // right edge alone is enough. Left edge excluded: that is where cut bubbles end up.
        val nearRightEdge = block.box.right > pageWidth * 0.9f
        val soundsLikeSite = WATERMARK_WORDS.any { jamoSimilarity(t, it) >= 0.5f }
        val latinTail = LATIN_TAIL.containsMatchIn(t) && hasSourceScript(t)
        return nearRightEdge && (soundsLikeSite || latinTail)
    }

    /**
     * How alike two short Korean strings sound: syllables are split into their three jamo
     * (initial, vowel, final) and compared position by position. OCR misreads a logo as a
     * similar-looking syllable ("짬툰" read "짧둔"), which this catches where exact matching fails.
     */
    private fun jamoSimilarity(a: String, b: String): Float {
        val ja = a.filter { it in '가'..'힣' }.flatMap { jamo(it) }
        val jb = b.filter { it in '가'..'힣' }.flatMap { jamo(it) }
        if (ja.isEmpty() || jb.isEmpty() || ja.size != jb.size) return 0f
        return ja.zip(jb).count { it.first == it.second }.toFloat() / ja.size
    }

    private fun jamo(syllable: Char): List<Int> {
        val code = syllable.code - 0xAC00
        return listOf(code / (21 * 28), (code % (21 * 28)) / 28, code % 28)
    }

    private fun hasSourceScript(text: String): Boolean = when (sourceLanguage) {
        "ko" -> text.any { it in '가'..'힣' || it in 'ᄀ'..'ᇿ' || it in '㄰'..'㆏' }
        "ja" -> text.any { it in '぀'..'ヿ' || it in '一'..'鿿' }
        else -> true
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
                val (text, lineCount) = when {
                    a.text.contains(block.text) -> a.text to a.lineCount
                    block.text.contains(a.text) -> block.text to block.lineCount
                    else -> (a.text + " " + block.text) to (a.lineCount + block.lineCount)
                }
                merged[index] = OcrBlock(text, box, lineCount)
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

    private companion object {
        const val TILE_HEIGHT = 2048
        const val TILE_OVERLAP = 256
        const val MAX_PIXELS = 60_000_000L
        const val JPEG_QUALITY = 90
        const val MERGE_MAX_GAP_LINES = 0.8f
        const val MERGE_MIN_OVERLAP = 0.4f
        val URL_PATTERN = Regex("""(?i)([.,·．]\s*c[o0][mnr]{1,2}\b|\.net|\.org|\.kr|\.io|www\.|http)""")

        /** A short latin fragment glued to Hangul: the end of a ".com" that the OCR half read. */
        val LATIN_TAIL = Regex("""[A-Za-z]{2,3}$""")

        /** Site logos and "read it first on..." banners that scan sites stamp on pages. */
        val WATERMARK_WORDS = listOf(
            "뉴토끼", "구글검색", "구글검", "웹툰미리보기", "웹튼미리보기", "미리보기", "짬툰", "마나토끼", "북토끼", "툰코",
            "Newtoki", "Toonkor",
        )

        /** Pieces of the "가장 빠른 웹툰 미리보기 구글검색" banner; two of them together mean the banner. */
        val WATERMARK_PARTS = listOf("가장", "빠른", "웹툰", "웹튼", "미리", "보기", "구글")
    }
}
