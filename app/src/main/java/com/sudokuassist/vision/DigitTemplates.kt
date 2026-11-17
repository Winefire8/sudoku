package com.sudokuassist.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/**
 * Fallback digit classifier that works without ML Kit.
 *
 * Templates are rendered once from the platform's bold sans font (9 glyphs), normalised
 * to a 16x16 grid of "ink coverage" and compared against the cell glyph with a
 * normalised cross-correlation (mean removed, so stroke thickness / anti-aliasing matter
 * less than shape). It is intentionally tiny and fully offline.
 *
 * Measured on the bundled sample screenshot (30 givens): 30/30 correct, provided the
 * glyph crop has been cleaned of grid-line blobs by [SudokuGridDetector.cleanBorderBlobs].
 * Without that cleaning the same classifier drops to 70% — which is why the recogniser
 * always cleans first. Other fonts are *not* guaranteed; that is what ML Kit is for.
 *
 * ML Kit is preferred (see [SudokuRecognizer]); this classifier is used when the model
 * is unavailable, when the user disabled ML Kit, or to break ties on poor OCR results.
 */
object DigitTemplates {

    private const val GRID = 16
    private const val SIZE = 64

    private val templates: Array<FloatArray> by lazy { buildTemplates() }

    private fun buildTemplates(): Array<FloatArray> {
        val out = Array(9) { FloatArray(GRID * GRID) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = SIZE * 0.82f
            textAlign = Paint.Align.CENTER
        }
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        for (d in 1..9) {
            canvas.drawColor(Color.WHITE)
            val fm = paint.fontMetrics
            val baseline = SIZE / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(d.toString(), SIZE / 2f, baseline, paint)
            out[d - 1] = features(bmp)
        }
        bmp.recycle()
        return out
    }

    /** Normalised 16x16 ink coverage of a glyph bitmap (dark pixels on light background). */
    fun features(bmp: Bitmap): FloatArray {
        val f = FloatArray(GRID * GRID)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        // bounding box of the ink, so that the glyph fills the template
        var minX = bmp.width; var minY = bmp.height; var maxX = -1; var maxY = -1
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val c = px[y * bmp.width + x]
                val lum = (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114)
                if (lum < 128) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return f
        val w = (maxX - minX + 1).toFloat()
        val h = (maxY - minY + 1).toFloat()
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val c = px[y * bmp.width + x]
                val lum = (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114) / 255.0
                val ink = (1.0 - lum).toFloat()
                if (ink <= 0.05f) continue
                val gx = ((x - minX) / w * (GRID - 1)).toInt().coerceIn(0, GRID - 1)
                val gy = ((y - minY) / h * (GRID - 1)).toInt().coerceIn(0, GRID - 1)
                f[gy * GRID + gx] += ink
            }
        }
        // Normalise the ink coverage, then make the vector zero-mean so the correlation
        // compares shape rather than total darkness.
        var mx = 0f
        for (v in f) if (v > mx) mx = v
        if (mx > 0f) for (i in f.indices) f[i] /= mx
        var mean = 0f
        for (v in f) mean += v
        mean /= f.size
        for (i in f.indices) f[i] -= mean
        return f
    }

    data class Match(val digit: Int, val score: Float)

    /** Best matching digit and a normalised score 0..1. */
    fun classify(bmp: Bitmap): Match {
        val f = features(bmp)
        var bestDigit = 0
        var best = -1f
        for (d in 1..9) {
            val t = templates[d - 1]
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (i in f.indices) {
                dot += f[i] * t[i]
                na += f[i] * f[i]
                nb += t[i] * t[i]
            }
            val denom = kotlin.math.sqrt(na * nb)
            val score = if (denom <= 0f) 0f else dot / denom
            if (score > best) {
                best = score
                bestDigit = d
            }
        }
        return Match(bestDigit, best.coerceIn(0f, 1f))
    }

    /** Debug helper: PNG bytes of all templates, used by the unit test. */
    fun debugPng(): ByteArray {
        val bmp = Bitmap.createBitmap(GRID * 9, GRID, Bitmap.Config.ARGB_8888)
        val px = IntArray(bmp.width * bmp.height)
        for (d in 0 until 9) {
            for (y in 0 until GRID) {
                for (x in 0 until GRID) {
                    val v = (templates[d][y * GRID + x] * 255).toInt().coerceIn(0, 255)
                    px[y * bmp.width + d * GRID + x] = Color.rgb(255 - v, 255 - v, 255 - v)
                }
            }
        }
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }

    fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
