package mihon.feature.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream

/**
 * Yomikae: first translation engine, built only on Google ML Kit (no native library to build).
 *
 * Pipeline for one page:
 *   1. decode the image,
 *   2. OCR with the recognizer of the source language (tiled when the page is very tall),
 *   3. translate every text block (with a small cache so repeated sentences cost nothing),
 *   4. paint the translation over the original text (see [PageRenderer]),
 *   5. encode as JPEG.
 *
 * One instance is meant to live for the duration of a job: the ML Kit clients and the
 * translation cache are reused across pages. Call [close] when done.
 */
class MlKitPageTranslator(
    private val sourceLanguage: String,
    private val targetLanguage: String,
) : Closeable {

    private val recognizer: TextRecognizer = when (sourceLanguage) {
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(requireNotNull(TranslateLanguage.fromLanguageTag(sourceLanguage)))
            .setTargetLanguage(requireNotNull(TranslateLanguage.fromLanguageTag(targetLanguage)))
            .build(),
    )

    private val renderer = PageRenderer()
    private val translationCache = HashMap<String, String>()

    /**
     * Makes sure the ML Kit translation model is on the device. Downloads it (about 30 MB per
     * language) the first time. Must be called once before [translatePage].
     */
    suspend fun prepare() {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    /**
     * Returns the translated page as JPEG bytes, or null when the page has no text worth
     * translating (the caller then keeps the original).
     */
    suspend fun translatePage(openStream: () -> InputStream): ByteArray? {
        val bitmap = decode(openStream) ?: return null
        try {
            val blocks = recognize(bitmap)
            if (blocks.isEmpty()) return null

            val translated = blocks.map { block ->
                PageRenderer.Block(
                    box = block.box,
                    lineCount = block.lineCount,
                    text = translate(block.text),
                )
            }.filter { it.text.isNotBlank() }
            if (translated.isEmpty()) return null

            val output = renderer.render(bitmap, translated)
            return ByteArrayOutputStream().use { stream ->
                output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                if (output !== bitmap) output.recycle()
                stream.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun translate(text: String): String {
        translationCache[text]?.let { return it }
        val result = translator.translate(text).await()
        translationCache[text] = result
        return result
    }

    /**
     * ML Kit shrinks very large images before reading them, which makes small dialogue text
     * unreadable on long webtoon strips. So tall pages are cut into overlapping horizontal
     * bands, each band is read separately, and every block is kept only from the band where its
     * centre is far from the cut.
     */
    private suspend fun recognize(bitmap: Bitmap): List<OcrBlock> {
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

    private fun decode(openStream: () -> InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream().use { BitmapFactory.decodeStream(it, null, bounds) }
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
        return openStream().use { BitmapFactory.decodeStream(it, null, options) }
    }

    override fun close() {
        recognizer.close()
        translator.close()
    }

    /** A text block in page coordinates: the text (lines joined), its box and its line count. */
    private class OcrBlock(val text: String, val box: Rect, val lineCount: Int)

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
    }
}
