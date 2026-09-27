package mihon.feature.translation.ocr

import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Yomikae: DBNet post-processing in pure Kotlin (no OpenCV, no pyclipper), following
 * PaddleOCR's pipeline: probability map -> threshold -> connected components -> convex hull ->
 * minimum-area rectangle -> box score check -> "unclip" expansion.
 *
 * Adapted from ciddwd/overlay-translator (Apache-2.0), without its column-splitting extras.
 */
object DbPostprocessor {

    /** Four corners, ordered left-top, right-top, right-bottom, left-bottom. */
    class Quad(val p0: PointF, val p1: PointF, val p2: PointF, val p3: PointF) {
        val width: Float get() = hypot(p1.x - p0.x, p1.y - p0.y)
        val height: Float get() = hypot(p3.x - p0.x, p3.y - p0.y)
        val minX: Float get() = minOf(p0.x, p1.x, p2.x, p3.x)
        val maxX: Float get() = maxOf(p0.x, p1.x, p2.x, p3.x)
        val minY: Float get() = minOf(p0.y, p1.y, p2.y, p3.y)
        val maxY: Float get() = maxOf(p0.y, p1.y, p2.y, p3.y)
    }

    private class IntPoint(val x: Int, val y: Int)

    /**
     * Extracts the text line quads from a probability map, mapped back to image coordinates
     * with [scaleX]/[scaleY].
     */
    fun extractQuads(
        probMap: Array<FloatArray>,
        scaleX: Float,
        scaleY: Float,
        binThresh: Float,
        scoreThresh: Float,
        unclipRatio: Float,
        minSide: Int = 3,
    ): List<Quad> {
        val h = probMap.size
        val w = probMap[0].size
        val visited = Array(h) { BooleanArray(w) }
        val result = ArrayList<Quad>()
        val maxUnclip = (minOf(w, h) * 0.05f).coerceAtLeast(4f)
        val stack = ArrayDeque<Int>()

        for (y0 in 0 until h) {
            for (x0 in 0 until w) {
                if (visited[y0][x0] || probMap[y0][x0] < binThresh) continue
                val boundary = ArrayList<IntPoint>()
                var size = 0
                stack.addLast(y0 * w + x0)
                visited[y0][x0] = true
                while (stack.isNotEmpty()) {
                    val idx = stack.removeLast()
                    val cx = idx % w
                    val cy = idx / w
                    size++
                    var isBoundary = false
                    for (k in 0 until 8) {
                        val nx = cx + DX[k]
                        val ny = cy + DY[k]
                        if (nx !in 0 until w || ny !in 0 until h) {
                            isBoundary = true
                            continue
                        }
                        if (probMap[ny][nx] < binThresh) {
                            isBoundary = true
                            continue
                        }
                        if (!visited[ny][nx]) {
                            visited[ny][nx] = true
                            stack.addLast(ny * w + nx)
                        }
                    }
                    if (isBoundary) boundary += IntPoint(cx, cy)
                }
                if (size < 16) continue
                val hull = convexHull(boundary)
                if (hull.size < 3) continue
                val quad = minAreaRect(hull) ?: continue
                if (minOf(quad.width, quad.height) < minSide) continue
                if (boxScore(probMap, quad) < scoreThresh) continue
                val u = unclip(quad, unclipRatio, maxUnclip)
                result += Quad(
                    PointF(u.p0.x * scaleX, u.p0.y * scaleY),
                    PointF(u.p1.x * scaleX, u.p1.y * scaleY),
                    PointF(u.p2.x * scaleX, u.p2.y * scaleY),
                    PointF(u.p3.x * scaleX, u.p3.y * scaleY),
                )
            }
        }
        return result
    }

    /** Andrew's monotone chain; returns the hull counter-clockwise. */
    private fun convexHull(points: List<IntPoint>): List<IntPoint> {
        if (points.size <= 2) return points
        val sorted = points.distinctBy { it.x.toLong() shl 32 or (it.y.toLong() and 0xffffffffL) }
            .sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size <= 2) return sorted
        val hull = ArrayList<IntPoint>()
        for (p in sorted) {
            while (hull.size >= 2 &&
                cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0
            ) {
                hull.removeAt(hull.size - 1)
            }
            hull += p
        }
        val lower = hull.size + 1
        for (i in sorted.size - 2 downTo 0) {
            val p = sorted[i]
            while (hull.size >= lower &&
                cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0
            ) {
                hull.removeAt(hull.size - 1)
            }
            hull += p
        }
        hull.removeAt(hull.size - 1)
        return hull
    }

    private fun cross(o: IntPoint, a: IntPoint, b: IntPoint): Long =
        (a.x - o.x).toLong() * (b.y - o.y) - (a.y - o.y).toLong() * (b.x - o.x)

    /** Rotating calipers: the smallest rectangle has a side collinear with a hull edge. */
    private fun minAreaRect(hull: List<IntPoint>): Quad? {
        var bestArea = Double.MAX_VALUE
        var best: Quad? = null
        val n = hull.size
        for (i in 0 until n) {
            val a = hull[i]
            val b = hull[(i + 1) % n]
            val dx = (b.x - a.x).toDouble()
            val dy = (b.y - a.y).toDouble()
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-6) continue
            val ux = dx / len
            val uy = dy / len
            val vx = -uy
            val vy = ux
            var minU = Double.MAX_VALUE
            var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE
            var maxV = -Double.MAX_VALUE
            for (p in hull) {
                val u = p.x * ux + p.y * uy
                val v = p.x * vx + p.y * vy
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            val rw = maxU - minU
            val rh = maxV - minV
            val area = rw * rh
            if (area < bestArea && rw > 1 && rh > 1) {
                bestArea = area
                fun toXY(u: Double, v: Double) = PointF((u * ux + v * vx).toFloat(), (u * uy + v * vy).toFloat())
                best = orderCorners(toXY(minU, minV), toXY(maxU, minV), toXY(maxU, maxV), toXY(minU, maxV))
            }
        }
        return best
    }

    /** PaddleOCR's corner order: two leftmost points give LT/LB, two rightmost give RT/RB. */
    private fun orderCorners(a: PointF, b: PointF, c: PointF, d: PointF): Quad {
        val sorted = listOf(a, b, c, d).sortedBy { it.x }
        val left = sorted.take(2).sortedBy { it.y }
        val right = sorted.drop(2).sortedBy { it.y }
        return Quad(left[0], right[0], right[1], left[1])
    }

    /** Mean probability inside the quad. */
    private fun boxScore(probMap: Array<FloatArray>, quad: Quad): Float {
        val h = probMap.size
        val w = probMap[0].size
        val x0 = quad.minX.toInt().coerceAtLeast(0)
        val y0 = quad.minY.toInt().coerceAtLeast(0)
        val x1 = quad.maxX.toInt().coerceAtMost(w - 1)
        val y1 = quad.maxY.toInt().coerceAtMost(h - 1)
        if (x1 <= x0 || y1 <= y0) return 0f
        var sum = 0.0
        var count = 0
        val poly = arrayOf(quad.p0, quad.p1, quad.p2, quad.p3)
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (inside(x + 0.5f, y + 0.5f, poly)) {
                    sum += probMap[y][x]
                    count++
                }
            }
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    private fun inside(x: Float, y: Float, poly: Array<PointF>): Boolean {
        var result = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val xi = poly[i].x
            val yi = poly[i].y
            val xj = poly[j].x
            val yj = poly[j].y
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi + 1e-9f) + xi) result = !result
            j = i
        }
        return result
    }

    /** Expands the rectangle by distance = area * ratio / perimeter (pyclipper's unclip). */
    private fun unclip(quad: Quad, ratio: Float, maxDistance: Float): Quad {
        val w = quad.width
        val h = quad.height
        if (w <= 1f || h <= 1f) return quad
        val dist = (w * h * ratio / (2 * (w + h))).coerceAtMost(maxDistance)
        val ux = (quad.p1.x - quad.p0.x) / w
        val uy = (quad.p1.y - quad.p0.y) / w
        val vx = (quad.p3.x - quad.p0.x) / h
        val vy = (quad.p3.y - quad.p0.y) / h
        fun shift(p: PointF, du: Float, dv: Float) = PointF(p.x + du * ux + dv * vx, p.y + du * uy + dv * vy)
        return Quad(
            shift(quad.p0, -dist, -dist),
            shift(quad.p1, dist, -dist),
            shift(quad.p2, dist, dist),
            shift(quad.p3, -dist, dist),
        )
    }

    private val DX = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
    private val DY = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
}
