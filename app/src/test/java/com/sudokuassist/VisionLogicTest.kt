package com.sudokuassist

import com.sudokuassist.vision.GridGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * JVM tests for the pure-Kotlin rules the detector depends on.
 *
 * These stay free of OpenCV and Android classes on purpose so `./gradlew test` runs
 * without a device. (The pixel pipeline — masks, lattice fit, warp — is exercised by the
 * in-app sample test, which needs OpenCV's native library.)
 */
class VisionLogicTest {

    /**
     * The same corner ordering the detector applies to every quad candidate:
     * top-left = min(x+y), bottom-right = max(x+y), top-right = max(x-y), bottom-left =
     * min(x-y). Kept in sync with `SudokuGridDetector.orderQuad`.
     */
    private fun orderQuad(pts: List<FloatArray>): FloatArray {
        val tl = pts.indices.minByOrNull { pts[it][0] + pts[it][1] }!!
        val br = pts.indices.maxByOrNull { pts[it][0] + pts[it][1] }!!
        val tr = pts.indices.maxByOrNull { pts[it][0] - pts[it][1] }!!
        val bl = pts.indices.minByOrNull { pts[it][0] - pts[it][1] }!!
        return floatArrayOf(
            pts[tl][0], pts[tl][1], pts[tr][0], pts[tr][1],
            pts[br][0], pts[br][1], pts[bl][0], pts[bl][1]
        )
    }

    @Test
    fun ordersCornersClockwiseFromTopLeft() {
        // Deliberately shuffled (clockwise here, counter-clockwise input order must not matter)
        val quad = listOf(
            floatArrayOf(900f, 100f),  // tr
            floatArrayOf(100f, 100f),  // tl
            floatArrayOf(100f, 900f),  // bl
            floatArrayOf(900f, 900f)   // br
        )
        val o = orderQuad(quad)
        assertEquals(100f, o[0], 0f) // tl.x
        assertEquals(100f, o[1], 0f)
        assertEquals(900f, o[2], 0f) // tr.x
        assertEquals(100f, o[3], 0f)
        assertEquals(900f, o[4], 0f) // br
        assertEquals(900f, o[5], 0f)
        assertEquals(100f, o[6], 0f) // bl
        assertEquals(900f, o[7], 0f)
    }

    @Test
    fun orderingIsIndependentOfInputRotation() {
        val base = listOf(
            floatArrayOf(100f, 100f), floatArrayOf(900f, 100f),
            floatArrayOf(900f, 900f), floatArrayOf(100f, 900f)
        )
        val reference = orderQuad(base)
        for (shift in 1..3) {
            val rotated = base.drop(shift) + base.take(shift)
            assertTrue(
                "rotation $shift changed the order",
                reference.contentEquals(orderQuad(rotated))
            )
        }
    }

    @Test
    fun orderedRotatedQuadProducesUsableGeometry() {
        // A board photographed with mild perspective: 720 px top edge, 1000 px bottom edge
        // (isPlausible allows an edge ratio down to 0.75, i.e. ~25% keystone).
        val o = orderQuad(
            listOf(
                floatArrayOf(860f, 100f), floatArrayOf(1000f, 1000f),
                floatArrayOf(140f, 100f), floatArrayOf(0f, 1000f)
            )
        )
        val g = GridGeometry(o)
        assertTrue("mild perspective must be accepted", g.isPlausible())
        // Cell (0,0) must sit in the top-left area of the board, (8,8) in the bottom-right.
        // Reference values of the projective (not affine) mapping — see the Python check in
        // the repo history; tolerance is generous so the test does not depend on the 3rd digit.
        val c0 = g.cellCenter(0, 0)
        assertTrue("cell(0,0)=${c0.toList()}", c0[0] < 260f && c0[1] < 160f)
        val c8 = g.cellCenter(8, 8)
        assertTrue("cell(8,8)=${c8.toList()}", c8[0] > 920f && c8[1] > 900f)
    }

    @Test
    fun cellCentresStayInsideTheBoardQuad() {
        val g = GridGeometry(
            orderQuad(
                listOf(
                    floatArrayOf(200f, 100f), floatArrayOf(800f, 130f),
                    floatArrayOf(820f, 760f), floatArrayOf(180f, 740f)
                )
            )
        )
        for (r in 0 until 9) {
            for (c in 0 until 9) {
                val p = g.cellCenter(r, c)
                assertTrue("cell($r,$c)=${p.toList()} outside board", p[0] in 150f..850f && p[1] in 80f..800f)
            }
        }
    }
}
