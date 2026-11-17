package com.sudokuassist.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.sudokuassist.vision.GridGeometry
import com.sudokuassist.vision.RecognizedGrid

/**
 * Transparent full-screen layer that draws the answers on top of the recognised cells.
 *
 * The view is laid out over the whole screen, so screenshot coordinates are simply
 * scaled by viewSize / screenshotSize. Nothing here is touchable — touches fall through
 * to the app below.
 */
class DigitOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** What to draw; null = nothing. */
    class State(
        val grid: RecognizedGrid?,
        val solutions: IntArray?,
        val showGivens: Boolean,
        val answerScale: Float,
        val message: String? = null,
        val messageIsError: Boolean = false,
        val highlightIndex: Int = -1
    )

    private var state: State? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        strokeJoin = Paint.Join.ROUND
    }
    private val givenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val boardOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(170, 255, 106, 61)
    }
    private val cellBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(40, 255, 106, 61)
    }
    private val messageBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 27, 31, 38)
    }
    private val messageText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val path = Path()
    private val rect = RectF()

    fun setState(s: State?) {
        state = s
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = state ?: return
        val grid = s.grid
        if (grid != null && s.solutions != null && grid.geometry != null) {
            drawGrid(canvas, grid, s)
        }
        val msg = s.message
        if (msg != null) {
            drawMessage(canvas, msg, s.messageIsError)
        }
    }

    private fun drawGrid(canvas: Canvas, grid: RecognizedGrid, s: State) {
        // Everything is scaled from screenshot space to this full-screen view. Using one
        // uniform factor keeps the digits square; the aspect ratio is identical in
        // practice because the overlay spans the same display as the screenshot.
        val sx = width.toFloat() / grid.imageWidth.coerceAtLeast(1)
        val sy = height.toFloat() / grid.imageHeight.coerceAtLeast(1)
        val scale = (sx + sy) / 2f
        val geometry = grid.geometry ?: return
        val cellSize = geometry.averageCellSize() * scale
        val textSize = cellSize * s.answerScale

        // Subtle frame so the user sees what was recognised.
        drawQuad(canvas, geometry, sx, sy, boardOutline, 3f)

        fill.textSize = textSize
        outline.textSize = textSize
        outline.strokeWidth = (textSize * 0.14f).coerceAtLeast(2f)
        givenPaint.textSize = textSize * 0.42f

        for (i in 0 until 81) {
            val r = i / 9
            val c = i % 9
            val given = grid.cells[i]
            val answer = s.solutions!![i]
            val p = geometry.cellCenter(r, c)
            val x = p[0] * sx
            val y = p[1] * sy
            val baseline = y + textSize * 0.36f

            if (i == s.highlightIndex) {
                val q = geometry.cellQuad(r, c)
                path.reset()
                path.moveTo(q[0] * sx, q[1] * sy)
                path.lineTo(q[2] * sx, q[3] * sy)
                path.lineTo(q[4] * sx, q[5] * sy)
                path.lineTo(q[6] * sx, q[7] * sy)
                path.close()
                canvas.drawPath(path, cellBox)
            }

            if (given != 0) {
                if (s.showGivens) {
                    givenPaint.color = Color.argb(150, 255, 106, 61)
                    canvas.drawText(given.toString(), x, y - textSize * 0.42f + textSize * 0.36f, givenPaint)
                }
                continue
            }
            if (answer == 0) continue
            val text = answer.toString()
            // Black stroke + bright fill: readable on light and dark boards.
            outline.color = Color.argb(235, 0, 0, 0)
            fill.color = Color.rgb(255, 232, 120)
            canvas.drawText(text, x, baseline, outline)
            canvas.drawText(text, x, baseline, fill)
        }
    }

    private fun drawQuad(canvas: Canvas, geometry: GridGeometry, sx: Float, sy: Float, paint: Paint, stroke: Float) {
        val c = geometry.corners
        path.reset()
        path.moveTo(c[0] * sx, c[1] * sy)
        for (i in 1 until 4) path.lineTo(c[i * 2] * sx, c[i * 2 + 1] * sy)
        path.close()
        paint.strokeWidth = stroke
        canvas.drawPath(path, paint)
    }

    private fun drawMessage(canvas: Canvas, text: String, isError: Boolean) {
        // The pill must fit the text: shrink the font until the line fits the panel.
        val maxWidth = width * 0.88f
        var size = messageTextSize()
        messageText.textSize = size
        while (size > 12f && messageText.measureText(text) > maxWidth - 48f) {
            size -= 1f
            messageText.textSize = size
        }
        val textWidth = messageText.measureText(text)
        val padH = size * 0.9f
        val padV = size * 0.7f
        val left = (width - textWidth) / 2f - padH
        val right = (width + textWidth) / 2f + padH
        val top = height * 0.82f
        rect.set(left, top, right, top + size + 2 * padV)
        canvas.drawRoundRect(rect, size, size, messageBg)
        messageText.color = if (isError) Color.rgb(255, 91, 91) else Color.rgb(236, 239, 245)
        val fm = messageText.fontMetrics
        val baseline = top + padV - fm.ascent
        canvas.drawText(text, width / 2f, baseline, messageText)
    }

    /** Message font size in px, derived from the screen size (14sp-ish reference). */
    private fun messageTextSize(): Float =
        (resources.displayMetrics.density * 15f).coerceAtLeast(24f)
}
