package com.sudokuassist.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a screenshot into a grid of digits.
 *
 * Roles of the two engines:
 *  - OpenCV finds the board and decides **which cells are filled** (fast, reliable).
 *  - ML Kit reads the digit glyphs from a zoomed crop per cell. Its bounding boxes are
 *    matched back to cells, which also corrects small grid-alignment errors.
 *  - [DigitTemplates] provides a fully offline fallback / tie-breaker, so recognition
 *    still works when ML Kit is disabled or unavailable.
 */
class SudokuRecognizer(
    private val useMlKit: Boolean = true
) {

    data class Output(
        val grid: RecognizedGrid,
        /** Cells where the two engines disagreed or where confidence was low. */
        val uncertain: BooleanArray
    )

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private companion object {
        /** OCR runs on the warped board scaled up by this factor. */
        const val OCR_ZOOM = 2
    }

    /**
     * Runs the full pipeline on a screenshot.
     * Blocking — call it from a background thread.
     */
    fun recognize(bitmap: Bitmap): Output? {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()

        val detection = SudokuGridDetector.detect(gray)
        if (detection == null) {
            gray.release()
            return null
        }
        val warped = detection.warpedCells
        val cellsOpenCv = readOccupancy(warped)
        warped.release()

        // The board is warped once to a 9*CELL square; both engines work on it.
        // OCR gets a 2x upscale because ML Kit wants reasonably large glyphs.
        val boardBitmap = warpBoardToBitmap(gray, detection.geometry)
        val bigBoard = if (useMlKit) {
            Bitmap.createScaledBitmap(
                boardBitmap, boardBitmap.width * OCR_ZOOM, boardBitmap.height * OCR_ZOOM, true
            )
        } else {
            boardBitmap
        }

        var mlCells = IntArray(81)
        var mlConfidence = FloatArray(81)
        if (useMlKit) {
            try {
                val ocr = runMlKit(bigBoard)
                if (ocr != null) {
                    mlCells = ocr.first
                    mlConfidence = ocr.second
                }
            } catch (t: Throwable) {
                // ML Kit is optional: fall back to the template matcher below
            }
        }
        if (bigBoard !== boardBitmap) bigBoard.recycle()

        val givens = IntArray(81)
        val confidence = FloatArray(81)
        val uncertain = BooleanArray(81)

        // glyph crops for the template fallback
        val canvasBitmap = boardBitmap
        val cellPx = canvasBitmap.width / 9

        for (i in 0 until 81) {
            val occupied = cellsOpenCv[i]
            if (!occupied) {
                givens[i] = 0
                continue
            }
            val mlDigit = mlCells[i]
            val mlConf = mlConfidence[i]
            val glyph = cropCell(canvasBitmap, i, cellPx)
            val tmpl = if (glyph != null) DigitTemplates.classify(glyph) else DigitTemplates.Match(0, 0f)
            glyph?.recycle()

            when {
                mlDigit in 1..9 && mlConf >= 0.55f && tmpl.digit == mlDigit -> {
                    givens[i] = mlDigit
                    confidence[i] = max(mlConf, tmpl.score)
                }
                mlDigit in 1..9 && mlConf >= 0.75f && tmpl.score < 0.55f -> {
                    givens[i] = mlDigit
                    confidence[i] = mlConf
                }
                tmpl.digit in 1..9 && tmpl.score >= 0.70f -> {
                    givens[i] = tmpl.digit
                    confidence[i] = tmpl.score * 0.9f
                }
                mlDigit in 1..9 -> {
                    givens[i] = mlDigit
                    confidence[i] = mlConf
                    uncertain[i] = true
                }
                tmpl.digit in 1..9 -> {
                    givens[i] = tmpl.digit
                    confidence[i] = tmpl.score
                    uncertain[i] = tmpl.score < 0.6f
                }
                else -> {
                    givens[i] = 0
                    uncertain[i] = true
                }
            }
        }
        boardBitmap.recycle()
        gray.release()

        val grid = RecognizedGrid(
            cells = givens,
            confidences = confidence,
            geometry = detection.geometry,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            quality = detection.quality,
            screenshot = bitmap
        )
        return Output(grid, uncertain)
    }

    fun close() {
        try {
            recognizer.close()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ occupancy

    /**
     * Filled/empty decision per cell (ported from the reference implementation):
     * per-cell Otsu mask, ignore specks, ignore components that touch the cell border
     * (those are grid lines), then require a glyph-like size.
     */
    private fun readOccupancy(warped: Mat): BooleanArray {
        val out = BooleanArray(81)
        val cell = warped.cols() / 9
        val margin = (cell * 0.10).roundToInt()
        val inner = Mat()
        for (r in 0 until 9) {
            for (c in 0 until 9) {
                val rect = org.opencv.core.Rect(c * cell + margin, r * cell + margin, cell - 2 * margin, cell - 2 * margin)
                inner.release()
                warped.submat(rect).copyTo(inner)
                val mask = SudokuGridDetector.cellInkMask(inner)
                val labels = Mat()
                val stats = Mat()
                val centroids = Mat()
                val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S)
                var bestArea = 0
                var bw = 0
                var bh = 0
                val minArea = max(6, (0.012 * inner.rows() * inner.cols()).toInt())
                for (i in 1 until n) {
                    val x = stats.get(i, 0)[0].toInt()
                    val y = stats.get(i, 1)[0].toInt()
                    val w = stats.get(i, 2)[0].toInt()
                    val h = stats.get(i, 3)[0].toInt()
                    val a = stats.get(i, 4)[0].toInt()
                    if (a < minArea) continue
                    if (x <= 0 || y <= 0 || x + w >= inner.cols() || y + h >= inner.rows()) continue
                    if (a > bestArea) {
                        bestArea = a
                        bw = w
                        bh = h
                    }
                }
                val frac = bestArea.toDouble() / (inner.rows() * inner.cols())
                val wRel = bw.toDouble() / inner.cols()
                val hRel = bh.toDouble() / inner.rows()
                out[r * 9 + c] = frac >= 0.02 && wRel >= 0.06 && hRel >= 0.22
                labels.release(); stats.release(); centroids.release(); mask.release()
            }
        }
        inner.release()
        return out
    }

    // ------------------------------------------------------------------ ML Kit

    /** @return cells + confidences, or null on failure. */
    private fun runMlKit(board: Bitmap): Pair<IntArray, FloatArray>? {
        val cells = IntArray(81)
        val conf = FloatArray(81)
        val image = InputImage.fromBitmap(board, 0)
        val result = try {
            Tasks.await(recognizer.process(image))
        } catch (t: Throwable) {
            return null
        }
        val cell = board.width / 9.0
        for (block in result.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val text = element.text.trim()
                    val digit = parseDigit(text)
                    if (digit == 0) continue
                    val bb = element.boundingBox ?: continue
                    // Map the element box (may cover several cells) onto the closest cell.
                    val cx = bb.exactCenterX().toDouble()
                    val cy = bb.exactCenterY().toDouble()
                    val col = (cx / cell).toInt().coerceIn(0, 8)
                    val row = (cy / cell).toInt().coerceIn(0, 8)
                    val idx = row * 9 + col
                    cells[idx] = digit
                    conf[idx] = element.confidence
                }
            }
        }
        return cells to conf
    }

    private fun parseDigit(text: String): Int {
        // common OCR mistakes inside a single character
        val t = text.trim()
        if (t.length == 1) {
            val ch = t[0]
            if (ch in '1'..'9') return ch - '0'
            when (ch) {
                'l', 'I', '|', 'i' -> return 1
                'Z', 'z' -> return 2
                'S', 's' -> return 5
                'G', 'b' -> return 6
                'B' -> return 8
                'g', 'q' -> return 9
                'o', 'O', 'D' -> return 0
            }
        }
        return 0
    }

    // ------------------------------------------------------------------ bitmaps

    private fun warpBoardToBitmap(gray: Mat, geometry: GridGeometry): Bitmap {
        val warped = SudokuGridDetector.warpToCells(gray, geometry)
        val bmp = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warped, bmp)
        warped.release()
        return bmp
    }

    private fun cropCell(board: Bitmap, index: Int, cell: Int): Bitmap? {
        val r = index / 9
        val c = index % 9
        val m = (cell * 0.12).roundToInt()
        val x = c * cell + m
        val y = r * cell + m
        val w = cell - 2 * m
        val h = cell - 2 * m
        if (w <= 2 || h <= 2 || x < 0 || y < 0 || x + w > board.width || y + h > board.height) return null
        // Binarise to black-on-white for the template matcher.
        val src = Bitmap.createBitmap(board, x, y, w, h)
        val gray = matFrom(src)
        src.recycle()
        // Only the glyph: pieces of the grid lines touching the border would otherwise
        // stretch the bounding box over the whole cell (see cleanBorderBlobs).
        val mask = SudokuGridDetector.cleanBorderBlobs(SudokuGridDetector.cellInkMask(gray))
        gray.release()
        val out = Bitmap.createBitmap(mask.cols(), mask.rows(), Bitmap.Config.ARGB_8888)
        val mv = ByteArray(mask.rows() * mask.cols())
        mask.get(0, 0, mv)
        mask.release()
        val px = IntArray(mv.size)
        for (i in mv.indices) px[i] = if (mv[i].toInt() != 0) Color.BLACK else Color.WHITE
        out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun matFrom(bmp: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        return gray
    }
}
