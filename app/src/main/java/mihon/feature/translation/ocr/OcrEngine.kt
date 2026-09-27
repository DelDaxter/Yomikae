package mihon.feature.translation.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.Closeable

/** A piece of text found on a page: the text, its box in page pixels, and how many lines it spans. */
class OcrBlock(val text: String, val box: Rect, val lineCount: Int)

/**
 * Yomikae: text detection + recognition on one image. Second "plugin" role after the
 * translator. Engines: Google ML Kit (bundled), PaddleOCR through ONNX Runtime, later others.
 *
 * The image handed to [recognize] is at most a few thousand pixels tall (the page pipeline
 * cuts tall webtoon strips into bands); boxes are returned in that image's coordinates.
 */
interface OcrEngine : Closeable {

    /** Downloads models, opens sessions. Called once before the first page. */
    suspend fun prepare()

    suspend fun recognize(bitmap: Bitmap): List<OcrBlock>

    companion object {
        const val ENGINE_MLKIT = "mlkit"
        const val ENGINE_PADDLE = "paddle"
    }
}
