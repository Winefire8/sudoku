package com.sudokuassist.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Geometry of a detected Sudoku board on the screen.
 *
 * The four corners are in screenshot coordinates, clockwise starting top-left.
 * [cellPoint] maps a cell centre to screen coordinates, which is what the overlay
 * renderer uses to place the answers exactly over the recognised cells.
 */
class GridGeometry(
    val corners: FloatArray, // 8 floats: x0,y0,x1,y1,x2,y2,x3,y3 (tl, tr, br, bl)
) {
    init {
        require(corners.size == 8) { "corners must contain 8 coordinates" }
    }

    /** Simple 8x8 homography helpers (projective mapping of the unit square). */
    private val h: DoubleArray = computeHomography(corners)

    fun mapCell(u: Double, v: Double): FloatArray =
        applyHomography(h, u, v)

    /** Centre of cell (row, col) in screen pixels. */
    fun cellCenter(row: Int, col: Int): FloatArray =
        mapCell((col + 0.5) / 9.0, (row + 0.5) / 9.0)

    /** Screen quad of one cell, clockwise from top-left. */
    fun cellQuad(row: Int, col: Int): FloatArray {
        val u0 = col / 9.0
        val u1 = (col + 1) / 9.0
        val v0 = row / 9.0
        val v1 = (row + 1) / 9.0
        val p0 = mapCell(u0, v0)
        val p1 = mapCell(u1, v0)
        val p2 = mapCell(u1, v1)
        val p3 = mapCell(u0, v1)
        return floatArrayOf(p0[0], p0[1], p1[0], p1[1], p2[0], p2[1], p3[0], p3[1])
    }

    /** Average length of the board edges — used to scale fonts. */
    fun averageSide(): Float {
        val top = dist(0, 1)
        val bottom = dist(3, 2)
        val left = dist(0, 3)
        val right = dist(1, 2)
        return (top + bottom + left + right) / 4f
    }

    fun averageCellSize(): Float = averageSide() / 9f

    private fun dist(a: Int, b: Int): Float {
        val dx = corners[a * 2] - corners[b * 2]
        val dy = corners[a * 2 + 1] - corners[b * 2 + 1]
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun unionBounds(): RectF {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val x = corners[i * 2]
            val y = corners[i * 2 + 1]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        return RectF(minX, minY, maxX, maxY)
    }

    /**
     * True when the quad is sane enough to display answers.
     *
     * The opposite-edge check is deliberately loose (0.55): a phone photographed at an
     * angle behind glass easily loses 30% of the far edge, and rejecting those boards was
     * worse than the small alignment error they introduce. Only *strong* keystone, where
     * the far edge is less than half the near one, is refused.
     */
    fun isPlausible(): Boolean {
        val side = averageSide()
        if (side < 60f) return false
        val top = dist(0, 1)
        val bottom = dist(3, 2)
        val left = dist(0, 3)
        val right = dist(1, 2)
        if (minOf(top, bottom) < MIN_OPPOSITE_RATIO * maxOf(top, bottom)) return false
        if (minOf(left, right) < MIN_OPPOSITE_RATIO * maxOf(left, right)) return false
        val ar = ((top + bottom) / 2f) / ((left + right) / 2f)
        return ar in 0.7f..1.4f
    }

    fun copy(): GridGeometry = GridGeometry(corners.copyOf())

    companion object {
        /** Minimum ratio between opposite edges of a plausible board. */
        const val MIN_OPPOSITE_RATIO = 0.55f

        /**
         * Homography mapping the unit square (0,0)-(1,1) to the quad.
         * Returns 8 coefficients for the rational map:
         *   X = (a*u + b*v + c) / (g*u + h*v + 1)
         *   Y = (d*u + e*v + f) / (g*u + h*v + 1)
         */
        fun computeHomography(corners: FloatArray): DoubleArray {
            val x0 = corners[0].toDouble(); val y0 = corners[1].toDouble()
            val x1 = corners[2].toDouble(); val y1 = corners[3].toDouble()
            val x2 = corners[4].toDouble(); val y2 = corners[5].toDouble()
            val x3 = corners[6].toDouble(); val y3 = corners[7].toDouble()
            // Solve the 8x8 linear system for the unit square -> quad mapping.
            // Using the standard closed form for a square source.
            val dx1 = x1 - x2; val dx2 = x3 - x2; val sx = x0 - x1 + x2 - x3
            val dy1 = y1 - y2; val dy2 = y3 - y2; val sy = y0 - y1 + y2 - y3
            if (sx == 0.0 && sy == 0.0) {
                // affine
                return doubleArrayOf(
                    x1 - x0, x3 - x0, x0,
                    y1 - y0, y3 - y0, y0,
                    0.0, 0.0
                )
            }
            val det = dx1 * dy2 - dx2 * dy1
            if (kotlin.math.abs(det) < 1e-12) {
                return doubleArrayOf(x1 - x0, x3 - x0, x0, y1 - y0, y3 - y0, y0, 0.0, 0.0)
            }
            val g = (sx * dy2 - dx2 * sy) / det
            val hh = (dx1 * sy - sx * dy1) / det
            val a = x1 - x0 + g * x1
            val b = x3 - x0 + hh * x3
            val c = x0
            val d = y1 - y0 + g * y1
            val e = y3 - y0 + hh * y3
            val f = y0
            return doubleArrayOf(a, b, c, d, e, f, g, hh)
        }

        fun applyHomography(h: DoubleArray, u: Double, v: Double): FloatArray {
            val den = h[6] * u + h[7] * v + 1.0
            return if (kotlin.math.abs(den) < 1e-12) {
                floatArrayOf(h[2].toFloat(), h[5].toFloat())
            } else {
                floatArrayOf(
                    ((h[0] * u + h[1] * v + h[2]) / den).toFloat(),
                    ((h[3] * u + h[4] * v + h[5]) / den).toFloat()
                )
            }
        }
    }
}

/**
 * Everything the recognition pipeline produced for one screenshot.
 */
data class RecognizedGrid(
    /** 81 cells, 0 = empty. */
    val cells: IntArray,
    /** Per-cell confidence 0..1 (occupied cells only). */
    val confidences: FloatArray,
    /** Board corners, or null when no board is known yet (fresh manual editor). */
    val geometry: GridGeometry?,
    /** Screenshot size the geometry refers to. */
    val imageWidth: Int,
    val imageHeight: Int,
    /** 0..1 quality of the grid detection. */
    val quality: Float,
    /** Original screenshot (for the manual editor), may be null when memory is tight. */
    val screenshot: Bitmap? = null
) {
    fun givensCount(): Int = cells.count { it != 0 }

    fun copyCells(): IntArray = cells.copyOf()

    /**
     * Returns a copy that keeps only a downscaled screenshot (for the manual editor) and
     * scales the geometry with it. The caller keeps ownership of [screenshot] and is
     * responsible for recycling it — this copy never shares the original bitmap.
     */
    fun copyWithThumbnail(maxSide: Int = 1600): RecognizedGrid {
        val shot = screenshot
        if (shot == null || shot.isRecycled) return RecognizedGrid(
            cells, confidences, geometry, imageWidth, imageHeight, quality, null
        )
        val scale = minOf(1f, maxSide.toFloat() / maxOf(shot.width, shot.height))
        val thumb = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                shot,
                (shot.width * scale).toInt().coerceAtLeast(1),
                (shot.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            shot.copy(Bitmap.Config.ARGB_8888, false)
        }
        val src = geometry ?: return RecognizedGrid(
            cells, confidences, null, imageWidth, imageHeight, quality, thumb
        )
        val corners = FloatArray(8) { src.corners[it] * scale }
        return RecognizedGrid(
            cells = cells,
            confidences = confidences,
            geometry = GridGeometry(corners),
            imageWidth = (imageWidth * scale).toInt().coerceAtLeast(1),
            imageHeight = (imageHeight * scale).toInt().coerceAtLeast(1),
            quality = quality,
            screenshot = thumb
        )
    }

    /** Releases the screenshot this grid owns, if any. */
    fun recycleScreenshot() {
        screenshot?.let { if (!it.isRecycled) it.recycle() }
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Draws a translucent overlay bitmap used for debugging and for the in-app preview.
 * Not used in production rendering (the overlay draws text directly).
 */
object GridDebugRenderer {
    fun draw(frame: Bitmap, grid: RecognizedGrid): Bitmap {
        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.rgb(255, 106, 61)
        }
        val geometry = grid.geometry ?: return out
        val path = android.graphics.Path()
        path.moveTo(geometry.corners[0], geometry.corners[1])
        for (i in 1 until 4) path.lineTo(geometry.corners[i * 2], geometry.corners[i * 2 + 1])
        path.close()
        canvas.drawPath(path, paint)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = geometry.averageCellSize() * 0.6f
            isFakeBoldText = true
        }
        for (r in 0 until 9) for (c in 0 until 9) {
            val v = grid.cells[r * 9 + c]
            if (v == 0) continue
            val p = geometry.cellCenter(r, c)
            canvas.drawText(v.toString(), p[0], p[1] + text.textSize * 0.35f, text)
        }
        return out
    }
}
