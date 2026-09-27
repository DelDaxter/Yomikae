package mihon.feature.translation.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import mihon.feature.translation.await

/** Yomikae: Google ML Kit text recognition (bundled models, no download, fast, so-so on manga). */
class MlKitOcr(language: String) : OcrEngine {

    private val recognizer: TextRecognizer = when (language) {
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun prepare() = Unit

    override suspend fun recognize(bitmap: Bitmap): List<OcrBlock> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val text = block.lines.joinToString(" ") { it.text }.trim()
            if (text.isBlank()) return@mapNotNull null
            OcrBlock(text, Rect(box), block.lines.size.coerceAtLeast(1))
        }
    }

    override fun close() {
        recognizer.close()
    }
}
