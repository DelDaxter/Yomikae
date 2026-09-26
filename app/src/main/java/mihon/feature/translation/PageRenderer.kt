package mihon.feature.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Yomikae: paints translated text over the original text of a page.
 *
 * For each block: the area of the original text is covered with the colour found just around
 * it (white for most speech bubbles), then the translation is drawn inside that area with the
 * largest font size that fits.
 *
 * This is the simplest possible "inpainting": it works on plain bubbles, which is most of a
 * webtoon's dialogue, and leaves text drawn over artwork looking rough. A real inpainter is a
 * later engine.
 */
class PageRenderer {

    class Block(
        val box: Rect,
        val lineCount: Int,
        val text: String,
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    fun render(bitmap: Bitmap, blocks: List<Block>): Bitmap {
        val target = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(target)

        for (block in blocks) {
            val lineHeight = block.box.height().toFloat() / block.lineCount.coerceAtLeast(1)
            val padding = (lineHeight * 0.25f).coerceIn(4f, 24f)
            val area = RectF(block.box).apply {
                inset(-padding, -padding)
                intersect(0f, 0f, target.width.toFloat(), target.height.toFloat())
            }
            if (area.isEmpty) continue

            val background = sampleBackground(target, area)
            fillPaint.color = background
            canvas.drawRoundRect(area, padding, padding, fillPaint)

            textPaint.color = if (luminance(background) > 140) Color.BLACK else Color.WHITE
            drawFittedText(canvas, block.text, area, lineHeight)
        }
        return target
    }

    /**
     * Looks at the pixels on a thin ring just outside the area and takes the median colour, so a
     * few dark pixels (the bubble outline, a bit of artwork) do not spoil the estimate.
     */
    private fun sampleBackground(bitmap: Bitmap, area: RectF): Int {
        val left = (area.left - RING).toInt().coerceAtLeast(0)
        val top = (area.top - RING).toInt().coerceAtLeast(0)
        val right = (area.right + RING).toInt().coerceAtMost(bitmap.width - 1)
        val bottom = (area.bottom + RING).toInt().coerceAtMost(bitmap.height - 1)

        val reds = ArrayList<Int>()
        val greens = ArrayList<Int>()
        val blues = ArrayList<Int>()
        fun sample(x: Int, y: Int) {
            val c = bitmap.getPixel(x, y)
            reds += Color.red(c)
            greens += Color.green(c)
            blues += Color.blue(c)
        }
        for (x in left..right step SAMPLE_STEP) {
            sample(x, top)
            sample(x, bottom)
        }
        for (y in top..bottom step SAMPLE_STEP) {
            sample(left, y)
            sample(right, y)
        }
        if (reds.isEmpty()) return Color.WHITE
        return Color.rgb(median(reds), median(greens), median(blues))
    }

    private fun median(values: MutableList<Int>): Int {
        values.sort()
        return values[values.size / 2]
    }

    private fun luminance(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000

    /**
     * Starts from the original line height and shrinks the font until the wrapped text fits both
     * the width and the height of the area. Drawn centred.
     */
    private fun drawFittedText(canvas: Canvas, text: String, area: RectF, lineHeight: Float) {
        val maxWidth = (area.width() - 2 * INNER_MARGIN).toInt().coerceAtLeast(1)
        val maxHeight = area.height() - 2 * INNER_MARGIN

        var size = (lineHeight * 0.9f).coerceAtLeast(MIN_TEXT_SIZE)
        var layout: StaticLayout
        while (true) {
            textPaint.textSize = size
            layout = buildLayout(text, maxWidth)
            val widest = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
            if ((layout.height <= maxHeight && widest <= maxWidth) || size <= MIN_TEXT_SIZE) break
            size = (size - 1f).coerceAtLeast(MIN_TEXT_SIZE)
        }

        canvas.save()
        canvas.translate(
            area.left + INNER_MARGIN,
            area.top + INNER_MARGIN + ((maxHeight - layout.height) / 2f).coerceAtLeast(0f),
        )
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildLayout(text: String, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.05f)
            .build()

    private companion object {
        const val RING = 3f
        const val SAMPLE_STEP = 2
        const val INNER_MARGIN = 2f
        const val MIN_TEXT_SIZE = 9f
    }
}
