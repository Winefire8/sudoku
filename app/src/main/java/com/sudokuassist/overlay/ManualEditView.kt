package com.sudokuassist.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.sudokuassist.vision.GridGeometry

/**
 * Manual correction UI drawn on top of the screenshot crop.
 *
 * The recognised board is shown as a crop of the screenshot; the recognised digits are
 * drawn on top of it. Tapping a cell selects it (the selection is reported through
 * [OnCellSelected]); tapping an answer in the digit pad calls [setDigit].
 */
class ManualEditView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onCellSelected(index: Int)
        fun onGridChanged(cells: IntArray)
    }

    var listener: Listener? = null

    private var geometry: GridGeometry? = null
    private var crop: android.graphics.Bitmap? = null
    private var srcRect: RectF? = null
    private val cells = IntArray(81)
    private var selected = -1
    private var showDigits = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val selFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(90, 255, 106, 61)
    }
    private val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.rgb(255, 106, 61)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.argb(120, 255, 255, 255)
    }
    private val path = Path()
    private val rect = RectF()

    fun bind(bitmap: android.graphics.Bitmap?, gridGeometry: GridGeometry?, initial: IntArray) {
        crop = bitmap
        geometry = gridGeometry
        initial.copyInto(cells)
        selected = -1
        if (bitmap != null && gridGeometry != null) {
            val b = gridGeometry.unionBounds()
            val padX = b.width() * 0.02f
            val padY = b.height() * 0.02f
            srcRect = RectF(b.left - padX, b.top - padY, b.right + padX, b.bottom + padY)
        } else {
            srcRect = null
        }
        invalidate()
    }

    fun setShowDigits(show: Boolean) {
        showDigits = show
        invalidate()
    }

    fun digits(): IntArray = cells.copyOf()

    fun select(index: Int) {
        if (index in 0..80) {
            selected = index
            listener?.onCellSelected(index)
            invalidate()
        }
    }

    fun setDigit(digit: Int) {
        val i = selected
        if (i < 0) return
        cells[i] = digit.coerceIn(0, 9)
        invalidate()
        listener?.onGridChanged(cells.copyOf())
    }

    fun clearAll() {
        cells.fill(0)
        invalidate()
        listener?.onGridChanged(cells.copyOf())
    }

    fun rotateDigit() {
        val i = selected
        if (i < 0) return
        cells[i] = when (cells[i]) {
            in 1..8 -> cells[i] + 1
            9 -> 0
            else -> 1
        }
        invalidate()
        listener?.onGridChanged(cells.copyOf())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bg = RectF(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = Color.rgb(16, 18, 22)
        canvas.drawRect(bg, paint)

        val src = srcRect
        if (crop != null && src != null) {
            // keep the aspect ratio of the board crop
            val scale = Math.min(width.toFloat() / src.width(), height.toFloat() / src.height())
            val dstW = src.width() * scale
            val dstH = src.height() * scale
            val left = (width - dstW) / 2f
            val top = (height - dstH) / 2f
            rect.set(left, top, left + dstW, top + dstH)
            paint.alpha = 255
            val srcInt = android.graphics.Rect(
                src.left.toInt(), src.top.toInt(), src.right.toInt(), src.bottom.toInt()
            )
            canvas.drawBitmap(crop!!, srcInt, rect, paint)
            paint.alpha = 255
            drawCells(canvas, scale, left - src.left * scale, top - src.top * scale)
        } else {
            // Fallback: schematic 9x9 grid
            val size = Math.min(width, height).toFloat() * 0.9f
            val left = (width - size) / 2f
            val top = (height - size) / 2f
            val c = size / 9f
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL
            canvas.drawRect(left, top, left + size, top + size, paint)
            paint.style = Paint.Style.STROKE
            paint.color = Color.BLACK
            for (i in 0..9) {
                paint.strokeWidth = if (i % 3 == 0) 4f else 1.5f
                canvas.drawLine(left + i * c, top, left + i * c, top + size, paint)
                canvas.drawLine(left, top + i * c, left + size, top + i * c, paint)
            }
            text.textSize = c * 0.6f
            text.color = Color.BLACK
            for (i in 0 until 81) {
                val v = cells[i]
                if (v == 0) continue
                val r = i / 9; val cc = i % 9
                canvas.drawText(v.toString(), left + (cc + 0.5f) * c, top + (r + 0.62f) * c, text)
            }
            if (selected >= 0) {
                val r = selected / 9; val cc = selected % 9
                rect.set(left + cc * c, top + r * c, left + (cc + 1) * c, top + (r + 1) * c)
                canvas.drawRect(rect, selFill)
                canvas.drawRect(rect, selStroke)
            }
        }
    }

    private fun drawCells(canvas: Canvas, scale: Float, ox: Float, oy: Float) {
        val g = geometry ?: return
        val cell = g.averageCellSize() * scale
        text.textSize = cell * 0.62f
        text.color = Color.rgb(255, 232, 120)
        text.setShadowLayer(cell * 0.08f, 0f, 0f, Color.BLACK)
        for (i in 0 until 81) {
            val r = i / 9
            val c = i % 9
            val q = g.cellQuad(r, c)
            path.reset()
            path.moveTo(q[0] * scale + ox, q[1] * scale + oy)
            path.lineTo(q[2] * scale + ox, q[3] * scale + oy)
            path.lineTo(q[4] * scale + ox, q[5] * scale + oy)
            path.lineTo(q[6] * scale + ox, q[7] * scale + oy)
            path.close()
            if (i == selected) canvas.drawPath(path, selFill)
            canvas.drawPath(path, gridPaint)
            if (showDigits) {
                val v = cells[i]
                if (v != 0) {
                    val p = g.cellCenter(r, c)
                    canvas.drawText(
                        v.toString(),
                        p[0] * scale + ox,
                        p[1] * scale + oy + text.textSize * 0.36f,
                        text
                    )
                }
            }
        }
        text.clearShadowLayer()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val idx = hitTest(event.x, event.y)
            if (idx >= 0) select(idx)
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): Int {
        val g = geometry
        val src = srcRect
        if (g != null && src != null && crop != null) {
            val scale = Math.min(width.toFloat() / src.width(), height.toFloat() / src.height())
            val dstW = src.width() * scale
            val dstH = src.height() * scale
            val left = (width - dstW) / 2f
            val top = (height - dstH) / 2f
            val ox = left - src.left * scale
            val oy = top - src.top * scale
            // iterate cells and test the polygon (81 cells, cheap)
            for (i in 0 until 81) {
                val q = g.cellQuad(i / 9, i % 9)
                if (pointInQuad(x, y, q, scale, ox, oy)) return i
            }
            return -1
        }
        val size = Math.min(width, height).toFloat() * 0.9f
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        if (x < left || y < top || x > left + size || y > top + size) return -1
        val c = size / 9f
        val col = ((x - left) / c).toInt().coerceIn(0, 8)
        val row = ((y - top) / c).toInt().coerceIn(0, 8)
        return row * 9 + col
    }

    private fun pointInQuad(x: Float, y: Float, q: FloatArray, scale: Float, ox: Float, oy: Float): Boolean {
        var inside = false
        var j = 3
        for (i in 0 until 4) {
            val xi = q[i * 2] * scale + ox
            val yi = q[i * 2 + 1] * scale + oy
            val xj = q[j * 2] * scale + ox
            val yj = q[j * 2 + 1] * scale + oy
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi + 1e-6f) + xi) inside = !inside
            j = i
        }
        return inside
    }
}
