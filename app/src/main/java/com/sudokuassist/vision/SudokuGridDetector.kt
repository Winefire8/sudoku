package com.sudokuassist.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Locates a 9x9 Sudoku board on a screenshot using OpenCV.
 *
 * Algorithm — the numbers below were tuned and validated against a Python reference
 * implementation (see “Алгоритм распознавания” in README.md), the Kotlin port follows it
 * stage by stage:
 *
 * 1. Ink mask: adaptive threshold with both polarities + Otsu; keeps the sparse
 *    polarisation, so light *and* dark themes work without configuration.
 * 2. Fast path: horizontal/vertical line profiles of the whole screen; if a lattice of
 *    10 equally spaced lines fits both axes, the board is axis aligned and is found
 *    in a few milliseconds.
 * 3. Slow path: contour candidates (approxPolyDP / minAreaRect) → per-candidate warp →
 *    lattice fit. Handles rotation and perspective.
 * 4. Lattice fit: peaks of the morphological line mask are searched with a
 *    RANSAC-like procedure (pairs of peaks define the spacing, every consistent offset
 *    is scored); the result is refined with a least-squares line fit and a sub-pixel
 *    centre-of-mass correction.
 * 5. The perimeter corners of the fitted lattice are the board geometry; the cell grid
 *    is obtained by warping those four corners onto a 9*CELL px square.
 */
object SudokuGridDetector {

    const val PAD = 10
    const val CELL = 96
    const val CANVAS = CELL * 9

    /**
     * Minimum score for reporting a board. Validated against the reference test suite:
     * clean screenshots score ~0.9, real-but-imperfect ones ~0.5–0.7, and keyboards /
     * tables / empty screens stay below 0.3.
     */
    private const val MIN_DETECTION_SCORE = 0.45f

    /**
     * Minimum mean grey-level gap between the ink and the background inside one cell.
     * Below this the cell is treated as empty: Otsu always splits *something*, so without
     * the guard sensor noise in a blank cell turns into a "digit".
     */
    private const val MIN_CELL_CONTRAST = 35.0

    data class Result(
        val geometry: GridGeometry,
        val quality: Float,
        /** Warped 9*CELL square with the board, used for digit recognition. */
        val warpedCells: org.opencv.core.Mat,
        /** Working scale that was used for detection (screen px per detection px). */
        val workScale: Float
    )

    // ------------------------------------------------------------------ public API

    /**
     * @param gray single channel 8-bit screenshot
     * @return the best grid found, or null
     *
     * The returned [Result] owns its `warpedCells` matrix; every other candidate is
     * released here, so the caller only has to release what it gets back.
     */
    fun detect(gray: org.opencv.core.Mat): Result? {
        // Seeds: the axis-aligned fast path first (with its own confidence as a prior),
        // then contour quads. Same policy as the reference implementation: a good axis
        // fit wins, otherwise the best snapped quad wins.
        val seeds = ArrayList<Pair<Float, GridGeometry>>()
        val axis = axisAlignedCandidate(gray)
        if (axis != null) seeds.add(axis)
        for (q in contourCandidates(gray)) seeds.add(0f to q)
        if (seeds.isEmpty()) return null

        var best: Result? = null
        var bestScore = 0f
        for ((prior, seed) in seeds) {
            if (best != null && bestScore >= 0.9f) break
            val ref = refine(gray, seed, 2) ?: continue
            val score = 0.65f * ref.quality + 0.35f * min(1f, prior / 0.8f)
            if (best == null || score > bestScore) {
                best?.warpedCells?.release()
                best = ref
                bestScore = score
            } else {
                ref.warpedCells.release()
            }
        }
        val result = best ?: return null
        if (bestScore < MIN_DETECTION_SCORE) {
            result.warpedCells.release()
            return null
        }
        return result
    }

    // ------------------------------------------------------------------ masks

    /** Ink mask: works for text/lines on light and on dark backgrounds. */
    fun structMask(gray: org.opencv.core.Mat): org.opencv.core.Mat {
        val blurred = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.GaussianBlur(gray, blurred, org.opencv.core.Size(3.0, 3.0), 0.0)
        val inv = org.opencv.core.Mat()
        val nrm = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.adaptiveThreshold(
            blurred, inv, 255.0,
            org.opencv.imgproc.Imgproc.ADAPTIVE_THRESH_MEAN_C,
            org.opencv.imgproc.Imgproc.THRESH_BINARY_INV, 31, 3.0
        )
        org.opencv.imgproc.Imgproc.adaptiveThreshold(
            blurred, nrm, 255.0,
            org.opencv.imgproc.Imgproc.ADAPTIVE_THRESH_MEAN_C,
            org.opencv.imgproc.Imgproc.THRESH_BINARY, 31, 3.0
        )
        val m = if (org.opencv.core.Core.countNonZero(inv) <= org.opencv.core.Core.countNonZero(nrm)) inv else nrm
        val otsu = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.threshold(
            blurred, otsu, 0.0, 255.0,
            org.opencv.imgproc.Imgproc.THRESH_BINARY_INV + org.opencv.imgproc.Imgproc.THRESH_OTSU
        )
        if (org.opencv.core.Core.countNonZero(otsu) > otsu.rows() * otsu.cols() / 2) {
            org.opencv.core.Core.bitwise_not(otsu, otsu)
        }
        val out = org.opencv.core.Mat()
        org.opencv.core.Core.bitwise_or(m, otsu, out)
        blurred.release(); inv.release(); nrm.release(); otsu.release()
        return out
    }

    /**
     * Per-cell ink mask (Otsu inside the cell, ink = the minority class).
     *
     * An empty cell has almost no difference between the two Otsu classes, so a contrast
     * guard returns an empty mask instead of amplifying sensor noise, and a 2x2 OPEN drops
     * single-pixel speckles before the component analysis.
     *
     * Measured on the bundled sample (30 givens, `tools/verify_pipeline.py`): without the
     * guard 17 of 51 empty cells are reported as occupied; with it, 0 — and the outcome is
     * unchanged from 25 to 80 grey levels, so the threshold is not a knife edge. Adding
     * sensor noise (sigma 4..12) keeps the guarded version at 0 errors.
     */
    fun cellInkMask(gray: org.opencv.core.Mat): org.opencv.core.Mat {
        val blurred = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.GaussianBlur(gray, blurred, org.opencv.core.Size(5.0, 5.0), 0.0)
        val bin = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.threshold(
            blurred, bin, 0.0, 255.0,
            org.opencv.imgproc.Imgproc.THRESH_BINARY + org.opencv.imgproc.Imgproc.THRESH_OTSU
        )
        val total = bin.rows() * bin.cols()
        if (total == 0) {
            blurred.release()
            bin.release()
            return org.opencv.core.Mat()
        }
        val bright = org.opencv.core.Core.countNonZero(bin)   // THRESH_BINARY keeps the bright side
        if (bright == 0 || bright == total) {
            blurred.release()
            bin.release()
            return org.opencv.core.Mat()                     // flat cell -> no ink at all
        }
        // ink = the minority class; the glyph is always smaller than the background
        val ink: org.opencv.core.Mat
        val inkCount: Int
        if (bright > total - bright) {
            ink = org.opencv.core.Mat()
            org.opencv.core.Core.bitwise_not(bin, ink)
            inkCount = total - bright
        } else {
            ink = bin.clone()
            inkCount = bright
        }
        bin.release()
        // contrast guard: without a real gap between the classes this is noise, not a digit
        val inkMean = org.opencv.core.Core.mean(blurred, ink).`val`[0]
        val allMean = org.opencv.core.Core.mean(blurred).`val`[0]
        val bgCount = total - inkCount
        val bgMean = if (bgCount > 0) (allMean * total - inkMean * inkCount) / bgCount else allMean
        blurred.release()
        if (kotlin.math.abs(inkMean - bgMean) < MIN_CELL_CONTRAST) {
            ink.release()
            return org.opencv.core.Mat()
        }
        val opened = org.opencv.core.Mat()
        val kernel = org.opencv.imgproc.Imgproc.getStructuringElement(
            org.opencv.imgproc.Imgproc.MORPH_RECT, org.opencv.core.Size(2.0, 2.0)
        )
        org.opencv.imgproc.Imgproc.morphologyEx(ink, opened, org.opencv.imgproc.Imgproc.MORPH_OPEN, kernel)
        ink.release()
        kernel.release()
        return opened
    }

    /**
     * Removes ink components that touch the cell border — those are pieces of the grid
     * lines, not the glyph. Without this the glyph's bounding box grows to the whole cell
     * and the template matcher (and the ML Kit crop) is fed garbage.
     */
    fun cleanBorderBlobs(mask: org.opencv.core.Mat, border: Int = 1): org.opencv.core.Mat {
        val labels = org.opencv.core.Mat()
        val stats = org.opencv.core.Mat()
        val centroids = org.opencv.core.Mat()
        val n = runCatching {
            org.opencv.imgproc.Imgproc.connectedComponentsWithStats(
                mask, labels, stats, centroids, 8, org.opencv.core.CvType.CV_32S
            )
        }.getOrDefault(0)
        if (n <= 1) {
            labels.release(); stats.release(); centroids.release()
            return mask
        }
        val h = mask.rows()
        val w = mask.cols()
        val drop = BooleanArray(n)
        for (i in 1 until n) {
            val x = stats.get(i, 0)[0].toInt()
            val y = stats.get(i, 1)[0].toInt()
            val bw = stats.get(i, 2)[0].toInt()
            val bh = stats.get(i, 3)[0].toInt()
            drop[i] = x <= border || y <= border || x + bw >= w - border || y + bh >= h - border
        }
        val labelBytes = ByteArray(w * h * 4)
        labels.get(0, 0, labelBytes)
        val pix = ByteArray(w * h)
        mask.get(0, 0, pix)
        var changed = false
        for (p in 0 until w * h) {
            if (pix[p].toInt() == 0) continue
            val label = (labelBytes[p * 4].toInt() and 0xFF) or
                ((labelBytes[p * 4 + 1].toInt() and 0xFF) shl 8) or
                ((labelBytes[p * 4 + 2].toInt() and 0xFF) shl 16) or
                ((labelBytes[p * 4 + 3].toInt() and 0xFF) shl 24)
            if (label in 0 until n && drop[label]) {
                pix[p] = 0
                changed = true
            }
        }
        labels.release(); stats.release(); centroids.release()
        if (!changed) return mask
        mask.put(0, 0, pix)
        return mask
    }

    // ------------------------------------------------------------------ stage A

    /** @return the axis-aligned board and the confidence of the lattice fit (0..1). */
    private fun axisAlignedCandidate(gray: org.opencv.core.Mat): Pair<Float, GridGeometry>? {
        val mask = structMask(gray)
        val h = gray.rows()
        val w = gray.cols()
        // A vertical stroke runs along y (max length h), a horizontal one along x
        // (max length w); the opening kernels must match or only digits survive.
        val kVert = max(7, h / 8)
        val kHorz = max(7, w / 8)
        val prof = lineProfiles(mask, kVert, kHorz)
        mask.release()
        val lc = fitLattice(prof.cols, null, 0.22f, 1.0f, 9, 0.0f) ?: return null
        val lr = fitLattice(prof.rows, null, 0.22f, 1.0f, 9, 0.0f) ?: return null
        if (abs(lc.s - lr.s) > 0.06 * max(lc.s, lr.s)) return null
        val x0 = lc.o
        val y0 = lr.o
        val x1 = lc.o + 9 * lc.s
        val y1 = lr.o + 9 * lr.s
        if (x0 < -4 || y0 < -4 || x1 > w + 4 || y1 > h + 4) return null
        val score = 0.5f * (lc.score + lr.score)
        return score to GridGeometry(floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))
    }


    // ------------------------------------------------------------------ stage B

    private fun contourCandidates(gray: org.opencv.core.Mat): List<GridGeometry> {
        val h = gray.rows()
        val w = gray.cols()
        val scale = min(1.0, 1100.0 / max(h, w))
        val work = org.opencv.core.Mat()
        if (scale < 1.0) {
            org.opencv.imgproc.Imgproc.resize(
                gray, work, org.opencv.core.Size(w * scale, h * scale), 0.0, 0.0,
                org.opencv.imgproc.Imgproc.INTER_AREA
            )
        } else {
            gray.copyTo(work)
        }
        val mask = structMask(work)
        val closed = org.opencv.core.Mat()
        val kernel = org.opencv.imgproc.Imgproc.getStructuringElement(
            org.opencv.imgproc.Imgproc.MORPH_RECT, org.opencv.core.Size(3.0, 3.0)
        )
        org.opencv.imgproc.Imgproc.morphologyEx(mask, closed, org.opencv.imgproc.Imgproc.MORPH_CLOSE, kernel)
        val quads = ArrayList<Pair<FloatArray, Float>>()
        val area = work.rows().toDouble() * work.cols()
        for (m in arrayOf(mask, closed)) {
            val contours = ArrayList<org.opencv.core.MatOfPoint>()
            val hierarchy = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.findContours(
                m.clone(), contours, hierarchy,
                org.opencv.imgproc.Imgproc.RETR_LIST, org.opencv.imgproc.Imgproc.CHAIN_APPROX_SIMPLE
            )
            for (c in contours) {
                val a = org.opencv.imgproc.Imgproc.contourArea(c)
                if (a < 0.01 * area || a > 0.97 * area) continue
                // arcLength/approxPolyDP work on the float variant, not on MatOfPoint.
                val curve = org.opencv.core.MatOfPoint2f(*c.toArray())
                val peri = org.opencv.imgproc.Imgproc.arcLength(curve, true)
                var quad: FloatArray? = null
                for (eps in doubleArrayOf(0.01, 0.02, 0.03, 0.05, 0.08)) {
                    val approx = org.opencv.core.MatOfPoint2f()
                    org.opencv.imgproc.Imgproc.approxPolyDP(curve, approx, eps * peri, true)
                    if (approx.total().toInt() == 4 && org.opencv.imgproc.Imgproc.isContourConvex(
                            org.opencv.core.MatOfPoint(*approx.toArray())
                        )
                    ) {
                        quad = orderQuad(approx.toArray().map { floatArrayOf(it.x.toFloat(), it.y.toFloat()) })
                        break
                    }
                }
                if (quad == null) {
                    val rect = org.opencv.imgproc.Imgproc.minAreaRect(org.opencv.core.MatOfPoint2f(*c.toArray()))
                    if (rect.size.width * rect.size.height >= 0.01 * area &&
                        min(rect.size.width, rect.size.height) > 30
                    ) {
                        quad = orderQuad(boxPoints(rect))
                    }
                }
                curve.release()
                if (quad != null) {
                    val scaled = FloatArray(8) { (quad[it] / scale).toFloat() }
                    quads.add(scaled to 0f)
                }
            }
            hierarchy.release()
            contours.forEach { it.release() }
        }
        mask.release(); closed.release(); kernel.release()

        // plausibility + de-duplication, larger quads first
        val good = ArrayList<Pair<FloatArray, Float>>()
        for ((q, _) in quads) {
            val sides = sideLengths(q)
            if (sides.min() < 60) continue
            val ar = (sides[0] + sides[2]) / max(1e-3f, sides[1] + sides[3])
            if (ar < 0.6f || ar > 1.7f) continue
            good.add(q to sides.min())
        }
        good.sortByDescending { it.second }
        val out = ArrayList<GridGeometry>()
        for ((q, _) in good) {
            if (out.none { maxCornerDiff(it.corners, q) <= 6f }) {
                out.add(GridGeometry(q))
                if (out.size >= 10) break
            }
        }
        return out
    }

    private fun boxPoints(rect: org.opencv.core.RotatedRect): List<FloatArray> {
        val pts = arrayOfNulls<org.opencv.core.Point>(4)
        org.opencv.core.RotatedRect(rect.center, rect.size, rect.angle).points(pts)
        return pts.filterNotNull().map { floatArrayOf(it.x.toFloat(), it.y.toFloat()) }
    }

    // ------------------------------------------------------------------ fit / refine

    data class Lattice(val o: Float, val s: Float, val count: Int, val contrast: Float, val score: Float)

    fun fitGrid(gray: org.opencv.core.Mat, quad: GridGeometry, allowEdge: Float): Result? {
        val warped = warpToCanvas(gray, quad)
        val mask = structMask(warped.canvas)
        val cellEst = warped.size / 9.0f
        val k = (cellEst * 0.62f).roundToInt().coerceIn(5, max(5, warped.size / 6))
        val prof = lineProfiles(mask, k, k)
        mask.release()
        val expect = (warped.size - 1) / 9.0f
        val lc = fitLattice(prof.cols, expect, 0.6f, 0.98f, 9, 0.05f) ?: run { warped.canvas.release(); return null }
        val lr = fitLattice(prof.rows, expect, 0.6f, 0.98f, 9, 0.05f) ?: run { warped.canvas.release(); return null }
        if (abs(lc.s - lr.s) > 0.06 * max(lc.s, lr.s)) { warped.canvas.release(); return null }
        val covC = max(abs(lc.o - PAD), abs(lc.o + 9 * lc.s - (warped.size - 1 + PAD)))
        val covR = max(abs(lr.o - PAD), abs(lr.o + 9 * lr.s - (warped.size - 1 + PAD)))
        val sMean = 0.5f * (lc.s + lr.s)
        val edge = max(covC, covR) / sMean
        if (edge > allowEdge) { warped.canvas.release(); return null }
        val score = 0.5f * (lc.score + lr.score) * (1f - min(1f, edge))
        val geom = LatticePair(lc, lr)
        val openCvQuad = quadFromLattice(quad, geom, warped.size)
        warped.canvas.release()
        val warpedCells = warpToCells(gray, openCvQuad)
        return Result(openCvQuad, score, warpedCells, warped.size.toFloat())
    }

    data class LatticePair(val cols: Lattice, val rows: Lattice)

    private fun refine(gray: org.opencv.core.Mat, seed: GridGeometry, iterations: Int): Result? {
        var fit = fitGrid(gray, seed, 0.80f) ?: return null
        var quad = fit.geometry
        val side0 = quad.averageSide()
        repeat(iterations) {
            if (abs(quad.averageSide() / side0 - 1f) > 0.2f) return@repeat
            val next = fitGrid(gray, quad, 0.80f) ?: return@repeat
            if (next.quality < fit.quality - 0.02f) {
                // Rejected candidate: its canvas must not leak.
                next.warpedCells.release()
                return@repeat
            }
            val moved = maxCornerDiff(next.geometry.corners, quad.corners)
            fit.warpedCells.release()
            fit = next
            quad = next.geometry
            if (moved < 0.5f) return@repeat
        }
        val tight = fitGrid(gray, quad, 0.30f)
        if (tight != null) {
            fit.warpedCells.release()
            fit = tight
        } else {
            fit = Result(fit.geometry, fit.quality * 0.75f, fit.warpedCells, fit.workScale)
        }
        return fit
    }

    private fun quadFromLattice(quad: GridGeometry, lat: LatticePair, size: Int): GridGeometry {
        val h = GridGeometry.computeHomography(quad.corners)
        val inv = invertHomography(h)
        val c = lat.cols; val r = lat.rows
        val pts = doubleArrayOf(
            c.o.toDouble(), r.o.toDouble(),
            c.o + 9.0 * c.s, r.o.toDouble(),
            c.o + 9.0 * c.s, r.o + 9.0 * r.s,
            c.o.toDouble(), r.o + 9.0 * r.s
        )
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val p = GridGeometry.applyHomography(inv, pts[i * 2], pts[i * 2 + 1])
            out[i * 2] = p[0]; out[i * 2 + 1] = p[1]
        }
        val points = ArrayList<FloatArray>(4)
        for (i in 0 until 4) points.add(floatArrayOf(out[i * 2], out[i * 2 + 1]))
        return GridGeometry(orderQuad(points))
    }

    private fun invertHomography(h: DoubleArray): DoubleArray {
        // 3x3 inverse; the 9 coefficients are a,b,c,d,e,f,g,h,1
        val a = h[0]; val b = h[1]; val c = h[2]
        val d = h[3]; val e = h[4]; val f = h[5]
        val g = h[6]; val hh = h[7]; val i = 1.0
        val det = a * (e * i - f * hh) - b * (d * i - f * g) + c * (d * hh - e * g)
        if (abs(det) < 1e-12) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
        val ia = (e * i - f * hh) / det
        val ib = (c * hh - b * i) / det
        val ic = (b * f - c * e) / det
        val id = (f * g - d * i) / det
        val ie = (a * i - c * g) / det
        val iff = (c * d - a * f) / det
        val ig = (d * hh - e * g) / det
        val ih = (b * g - a * hh) / det
        val ii = (a * e - b * d) / det
        if (abs(ii) < 1e-12) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
        return doubleArrayOf(ia / ii, ib / ii, ic / ii, id / ii, ie / ii, iff / ii, ig / ii, ih / ii)
    }

    // ------------------------------------------------------------------ warping

    data class WarpedCanvas(val canvas: org.opencv.core.Mat, val size: Int)

    private fun warpToCanvas(gray: org.opencv.core.Mat, quad: GridGeometry): WarpedCanvas {
        val side = quad.averageSide()
        val size = side.roundToInt().coerceIn(360, 900)
        val src = org.opencv.core.MatOfPoint2f(
            org.opencv.core.Point(quad.corners[0].toDouble(), quad.corners[1].toDouble()),
            org.opencv.core.Point(quad.corners[2].toDouble(), quad.corners[3].toDouble()),
            org.opencv.core.Point(quad.corners[4].toDouble(), quad.corners[5].toDouble()),
            org.opencv.core.Point(quad.corners[6].toDouble(), quad.corners[7].toDouble())
        )
        val dst = org.opencv.core.MatOfPoint2f(
            org.opencv.core.Point(PAD.toDouble(), PAD.toDouble()),
            org.opencv.core.Point((size - 1 + PAD).toDouble(), PAD.toDouble()),
            org.opencv.core.Point((size - 1 + PAD).toDouble(), (size - 1 + PAD).toDouble()),
            org.opencv.core.Point(PAD.toDouble(), (size - 1 + PAD).toDouble())
        )
        val m = org.opencv.imgproc.Imgproc.getPerspectiveTransform(src, dst)
        val out = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.warpPerspective(
            gray, out, m, org.opencv.core.Size((size + 2 * PAD).toDouble(), (size + 2 * PAD).toDouble()),
            org.opencv.imgproc.Imgproc.INTER_LINEAR
        )
        src.release(); dst.release(); m.release()
        return WarpedCanvas(out, size)
    }

    /** Warps the board onto a CELL*9 square so that every cell is exactly CELL px. */
    fun warpToCells(gray: org.opencv.core.Mat, quad: GridGeometry): org.opencv.core.Mat {
        val src = org.opencv.core.MatOfPoint2f(
            org.opencv.core.Point(quad.corners[0].toDouble(), quad.corners[1].toDouble()),
            org.opencv.core.Point(quad.corners[2].toDouble(), quad.corners[3].toDouble()),
            org.opencv.core.Point(quad.corners[4].toDouble(), quad.corners[5].toDouble()),
            org.opencv.core.Point(quad.corners[6].toDouble(), quad.corners[7].toDouble())
        )
        val dst = org.opencv.core.MatOfPoint2f(
            org.opencv.core.Point(0.0, 0.0),
            org.opencv.core.Point(CANVAS.toDouble(), 0.0),
            org.opencv.core.Point(CANVAS.toDouble(), CANVAS.toDouble()),
            org.opencv.core.Point(0.0, CANVAS.toDouble())
        )
        val m = org.opencv.imgproc.Imgproc.getPerspectiveTransform(src, dst)
        val out = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.warpPerspective(
            gray, out, m, org.opencv.core.Size(CANVAS.toDouble(), CANVAS.toDouble()),
            org.opencv.imgproc.Imgproc.INTER_LINEAR
        )
        src.release(); dst.release(); m.release()
        return out
    }

    // ------------------------------------------------------------------ profiles

    data class Profiles(val cols: FloatArray, val rows: FloatArray)

    fun lineProfiles(mask: org.opencv.core.Mat, kVert: Int, kHorz: Int): Profiles {
        val vK = org.opencv.imgproc.Imgproc.getStructuringElement(
            org.opencv.imgproc.Imgproc.MORPH_RECT, org.opencv.core.Size(1.0, kVert.toDouble())
        )
        val hK = org.opencv.imgproc.Imgproc.getStructuringElement(
            org.opencv.imgproc.Imgproc.MORPH_RECT, org.opencv.core.Size(kHorz.toDouble(), 1.0)
        )
        val vert = org.opencv.core.Mat()
        val horz = org.opencv.core.Mat()
        org.opencv.imgproc.Imgproc.morphologyEx(mask, vert, org.opencv.imgproc.Imgproc.MORPH_OPEN, vK)
        org.opencv.imgproc.Imgproc.morphologyEx(mask, horz, org.opencv.imgproc.Imgproc.MORPH_OPEN, hK)
        // Fraction of set pixels per column / per row. Core.reduce keeps this in native
        // code; a per-pixel loop over a full screenshot would cost tens of milliseconds.
        val cols = columnMeans(vert)
        val rows = columnMeans(horz, alongRows = true)
        vK.release(); hK.release(); vert.release(); horz.release()
        return Profiles(cols, rows)
    }

    /** Fraction of set pixels per column ([alongRows] = false) or per row (true). */
    private fun columnMeans(m: org.opencv.core.Mat, alongRows: Boolean = false): FloatArray {
        if (m.empty() || m.rows() == 0 || m.cols() == 0) return FloatArray(0)
        val sums = org.opencv.core.Mat()
        org.opencv.core.Core.reduce(
            m, sums,
            if (alongRows) 1 else 0,
            org.opencv.core.Core.REDUCE_SUM, org.opencv.core.CvType.CV_64F
        )
        val count = if (alongRows) m.rows() else m.cols()
        if (count == 0) {
            sums.release()
            return FloatArray(0)
        }
        // reduce() returns either 1xN or Nx1; reshape to a single row so one get() reads it.
        val flat = runCatching { sums.reshape(1, 1) }.getOrNull()
        val values = DoubleArray(count)
        if (flat != null) runCatching { flat.get(0, 0, values) }
        sums.release()
        val norm = 255.0 * if (alongRows) m.cols() else m.rows()
        return FloatArray(count) { i ->
            if (norm <= 0.0) 0f else (values[i] / norm).toFloat()
        }
    }

    // ------------------------------------------------------------------ lattice

    private fun localMaxima(p: FloatArray, minRatio: Float, nms: Int): FloatArray {
        var mx = 0f
        for (v in p) if (v > mx) mx = v
        if (mx <= 1e-6f) return FloatArray(0)
        val thr = mx * minRatio
        val cand = ArrayList<Int>()
        for (i in 1 until p.size - 1) {
            if (p[i] >= thr && p[i] >= p[i - 1] && p[i] >= p[i + 1]) cand.add(i)
        }
        cand.sortByDescending { p[it] }
        val keep = ArrayList<Int>()
        for (i in cand) if (keep.all { abs(it - i) > nms }) keep.add(i)
        keep.sort()
        return FloatArray(keep.size) { keep[it].toFloat() }
    }

    private fun segMax(p: FloatArray, center: Float, half: Float): Float {
        val a = max(0, (center - half).roundToInt())
        val b = min(p.size - 1, (center + half).roundToInt())
        if (b < a) return 0f
        var mx = 0f
        for (i in a..b) if (p[i] > mx) mx = p[i]
        return mx
    }

    private fun subpixel(p: FloatArray, center: Float, half: Float): Float {
        val a = max(0, kotlin.math.floor(center - half).toInt())
        val b = min(p.size - 1, kotlin.math.ceil(center + half).toInt())
        if (b <= a) return center
        var mx = 0f
        for (i in a..b) if (p[i] > mx) mx = p[i]
        var num = 0.0
        var den = 0.0
        for (i in a..b) {
            val w = (p[i] - 0.5f * mx).toDouble().coerceAtLeast(1e-6)
            num += i * w
            den += w
        }
        return if (den <= 0) center else (num / den).toFloat()
    }

    /**
     * Fits 10 equally spaced lines (index 0..9) to an ink profile.
     * [expectS] restricts the spacing search; [fuzz] widens that restriction.
     */
    fun fitLattice(profile: FloatArray, expectS: Float?, spanLo: Float, spanHi: Float, minCount: Int, fuzz: Float): Lattice? {
        if (profile.isEmpty()) return null
        var mx = 0f
        for (v in profile) if (v > mx) mx = v
        if (mx <= 0.12f) return null
        val n = profile.size
        val p = profile.copyOf()
        val b = min(3, max(2, n / 60))
        for (i in 0 until b) { p[i] = 0f; p[n - 1 - i] = 0f }
        mx = 0f
        for (v in p) if (v > mx) mx = v
        if (mx <= 0f) return null
        val pn = FloatArray(n) { p[it] / mx }
        val sLo: Float
        val sHi: Float
        if (expectS != null) {
            sLo = expectS * (0.88f - fuzz)
            sHi = expectS * (1.12f + fuzz)
        } else {
            sLo = max(4f, (n - 1) * spanLo / 9f)
            sHi = (n - 1) * spanHi / 9f
        }
        val peaks = localMaxima(pn, 0.13f, max(2, n / 150))
        var best: FloatArray? = null // score, offset, spacing
        val idx10 = DoubleArray(10) { it.toDouble() }

        fun consider(o: Float, s: Float): FloatArray {
            var cnt = 0
            var sum = 0f
            for (t in 0 until 10) {
                val v = segMax(pn, o + t * s, 0.20f * s)
                if (v >= 0.22f) cnt++
                sum += v
            }
            return floatArrayOf(cnt + sum / 10f, o, s)
        }

        fun maybe(sc: Float, o: Float, s: Float) {
            val current = best
            if (current == null || sc > current[0]) best = floatArrayOf(sc, o, s)
        }

        val peakCount = min(peaks.size, 26)
        if (peakCount >= 2) {
            // keep the strongest peaks if there are too many
            val order = (0 until peaks.size).sortedByDescending { pn[peaks[it].toInt()] }.take(peakCount)
            val pk = FloatArray(peakCount) { peaks[order[it]] }
            pk.sort()
            for (i in 0 until peakCount) {
                for (j in i + 1 until peakCount) {
                    val d = pk[j] - pk[i]
                    for (k in 1..9) {
                        val s = d / k
                        if (s < sLo || s > sHi) continue
                        for (k1 in 0..9 - k) {
                            val o = pk[i] - k1 * s
                            if (o < -0.8f * s || o + 9 * s > (n - 1) + 0.8f * s) continue
                            val r = consider(o, s)
                            maybe(r[0], o, s)
                        }
                    }
                }
            }
        }
        if (expectS != null) {
            var scanBest: FloatArray? = null
            val step = max(1f, expectS / 40f)
            var off = -0.55f * expectS
            while (off <= 0.55f * expectS + 1e-3f) {
                val r = consider(off, expectS)
                if (scanBest == null || r[0] > scanBest[0]) scanBest = r
                off += step
            }
            val scan = scanBest
            val current = best
            if (scan != null && (current == null || scan[0] >= current[0] - 0.4f)) best = scan
        }
        val chosen = best ?: return null
        var o = chosen[1]
        var s = chosen[2]
        var count = 0
        // sub-pixel least squares refinement using the detected inliers
        var sxx = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxy = 0.0
        for (t in 0 until 10) {
            val pred = o + t * s
            if (segMax(pn, pred, 0.24f * s) >= 0.22f) {
                val x = subpixel(pn, pred, 0.22f * s)
                count++
                val xt = t.toDouble()
                sxx += xt * xt; sx += xt; sy += x; sxy += xt * x
            }
        }
        if (count >= 2) {
            val den = count * sxx - sx * sx
            if (abs(den) > 1e-9) {
                val s2 = (count * sxy - sx * sy) / den
                val o2 = (sy - s2 * sx) / count
                if (s2 > 4 && abs(s2 - s) < 0.25 * s) {
                    o = o2.toFloat(); s = s2.toFloat()
                }
            }
        }
        if (s < 4f) return null
        if (count < minCount) return null
        var lineV = 0f
        var midV = 0f
        for (t in 0 until 10) lineV += segMax(pn, o + t * s, 0.14f * s)
        for (t in 0 until 9) midV += segMax(pn, o + (t + 0.5f) * s, 0.14f * s)
        lineV /= 10f
        midV /= 9f
        val contrast = lineV - midV
        if (contrast < 0.12f) return null
        val score = (count / 10f) * min(1f, contrast / 0.5f)
        return Lattice(o, s, count, contrast, score)
    }

    // ------------------------------------------------------------------ geometry helpers

    fun orderQuad(pts: List<FloatArray>): FloatArray {
        require(pts.size == 4)
        val s = pts.mapIndexed { i, p -> i to (p[0] + p[1]) }
        val d = pts.mapIndexed { i, p -> i to (p[0] - p[1]) }
        val tl = s.minByOrNull { it.second }!!.first
        val br = s.maxByOrNull { it.second }!!.first
        val tr = d.maxByOrNull { it.second }!!.first
        val bl = d.minByOrNull { it.second }!!.first
        return floatArrayOf(
            pts[tl][0], pts[tl][1],
            pts[tr][0], pts[tr][1],
            pts[br][0], pts[br][1],
            pts[bl][0], pts[bl][1]
        )
    }

    fun sideLengths(q: FloatArray): FloatArray {
        fun d(a: Int, b: Int): Float {
            val dx = q[a * 2] - q[b * 2]
            val dy = q[a * 2 + 1] - q[b * 2 + 1]
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        return floatArrayOf(d(0, 1), d(1, 2), d(2, 3), d(3, 0))
    }

    fun maxCornerDiff(a: FloatArray, b: FloatArray): Float {
        var mx = 0f
        for (i in 0 until 4) {
            val dx = a[i * 2] - b[i * 2]
            val dy = a[i * 2 + 1] - b[i * 2 + 1]
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d > mx) mx = d
        }
        return mx
    }
}
