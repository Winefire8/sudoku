package com.sudokuassist

import com.sudokuassist.vision.GridGeometry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the screen-space mapping that positions the answer digits.
 * A wrong homography here means answers land in the wrong cells — the one thing the
 * overlay must never do.
 */
class GeometryTest {

    private fun square(x0: Float, y0: Float, size: Float) =
        GridGeometry(floatArrayOf(x0, y0, x0 + size, y0, x0 + size, y0 + size, x0, y0 + size))

    @Test
    fun affineQuadMapsCornersExactly() {
        val g = square(100f, 200f, 900f)
        assertArrayEquals2(floatArrayOf(100f, 200f), g.mapCell(0.0, 0.0))
        assertArrayEquals2(floatArrayOf(1000f, 200f), g.mapCell(1.0, 0.0))
        assertArrayEquals2(floatArrayOf(1000f, 1100f), g.mapCell(1.0, 1.0))
        assertArrayEquals2(floatArrayOf(100f, 1100f), g.mapCell(0.0, 1.0))
    }

    @Test
    fun cellCentresLandInTheMiddleOfCells() {
        val g = square(0f, 0f, 900f)
        assertArrayEquals2(floatArrayOf(50f, 50f), g.cellCenter(0, 0))
        assertArrayEquals2(floatArrayOf(850f, 850f), g.cellCenter(8, 8))
        assertArrayEquals2(floatArrayOf(250f, 50f), g.cellCenter(0, 2))
        assertEquals(100f, g.averageCellSize(), 0.01f)
    }

    @Test
    fun perspectiveQuadIsMappedProjectively() {
        // Trapezoid: top edge narrower than the bottom edge.
        val g = GridGeometry(floatArrayOf(200f, 100f, 800f, 100f, 1000f, 1000f, 0f, 1000f))
        assertArrayEquals2(floatArrayOf(200f, 100f), g.mapCell(0.0, 0.0))
        assertArrayEquals2(floatArrayOf(1000f, 1000f), g.mapCell(1.0, 1.0))
        // Projective, not affine: the middle cell of a trapezoid sits above the
        // arithmetic middle of the top/bottom edges (100 and 1000 -> 437.5, not 550).
        val c = g.cellCenter(4, 4)
        assertEquals(500f, c[0], 0.5f)
        assertEquals(437.5f, c[1], 0.5f)
    }

    @Test
    fun rowsGetWiderTowardsTheBottomOfATrapezoid() {
        val g = GridGeometry(floatArrayOf(200f, 100f, 800f, 100f, 1000f, 1000f, 0f, 1000f))
        fun rowWidth(row: Int): Float {
            val left = g.cellQuad(row, 0)
            val right = g.cellQuad(row, 8)
            return right[2] - left[0]
        }
        assertTrue("row widths should grow: ${rowWidth(0)} -> ${rowWidth(8)}", rowWidth(8) > rowWidth(0))
    }

    @Test
    fun cellQuadTilesWithoutGaps() {
        val g = square(10f, 20f, 810f)
        val a = g.cellQuad(0, 0)   // tl, tr, br, bl
        val b = g.cellQuad(0, 1)
        assertEquals(a[2], b[0], 0.01f) // top-right of cell 0 == top-left of cell 1
        assertEquals(a[3], b[1], 0.01f)
        assertEquals(a[4], b[6], 0.01f) // bottom-right == bottom-left
        assertEquals(a[5], b[7], 0.01f)
    }

    @Test
    fun plausibilityRejectsDegenerateQuads() {
        assertTrue(square(0f, 0f, 900f).isPlausible())
        assertFalse(square(0f, 0f, 40f).isPlausible())   // too small
        // Collapsed side: not a board at all.
        assertFalse(GridGeometry(floatArrayOf(0f, 0f, 900f, 0f, 10f, 500f, 0f, 900f)).isPlausible())
        // Corners are tl, tr, br, bl — the far edge is the *top* one here, so a keystone
        // shows up as top/bottom != 1.
        // Extreme keystone (far edge is 40% of the near one) is refused…
        assertFalse(
            GridGeometry(floatArrayOf(300f, 100f, 700f, 100f, 1000f, 1000f, 0f, 1000f)).isPlausible()
        )
        // …but a 25% keystone, which is normal for a phone at an angle, is accepted.
        assertTrue(
            GridGeometry(floatArrayOf(125f, 100f, 875f, 100f, 1000f, 1000f, 0f, 1000f)).isPlausible()
        )
    }

    @Test
    fun geometryOwnsItsCorners() {
        val c = floatArrayOf(10f, 20f, 110f, 25f, 120f, 220f, 5f, 210f)
        val g = GridGeometry(c.copyOf())
        assertArrayEquals(c, g.corners, 0.0f)
        // copy() must be independent of the original object
        val copy = g.copy()
        assertEquals(10f, copy.corners[0], 0.0f)
    }

    private fun assertArrayEquals2(expected: FloatArray, actual: FloatArray) {
        assertEquals("x", expected[0], actual[0], 0.05f)
        assertEquals("y", expected[1], actual[1], 0.05f)
    }
}
