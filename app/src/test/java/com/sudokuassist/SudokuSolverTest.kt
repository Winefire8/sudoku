package com.sudokuassist

import com.sudokuassist.solver.SolveResult
import com.sudokuassist.solver.SudokuSolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the solver — no Android dependencies.
 *
 * Run with:  ./gradlew test
 */
class SudokuSolverTest {

    private fun grid(s: String): IntArray {
        val digits = s.filter { it.isDigit() }
        require(digits.length == 81) { "need 81 digits, got ${digits.length}" }
        return IntArray(81) { digits[it] - '0' }
    }

    private val classic =
        "530070000600195000098000060800060003400803001700020006060000280000419005000080079"
    private val classicSolution =
        "534678912672195348198342567859761423426853791713924856961537284287419635345286179"

    /** A puzzle with no duplicates but no completion either (one given of the classic altered). */
    private val unsolvable =
        "530070000600395000098000060800060003400803001700020006060000280000419005000080079"

    /** Published "hardest" boards: the node limit must never be what stops the search. */
    @Test
    fun solvesPublishedHardPuzzles() {
        val hard = listOf(
            // Platinum Blonde
            "000000012000000003002300400001800005060070800000009000008500000900040500470006000",
            // Golden Nugget
            "000000039000001005003050800008090006070002000100400000009080050020000600400700000",
            // Easter Monster
            "100000002090400050006000700050903000000070000000850040700000600030009080002000001",
            // Inkala 2012
            "800000000003600000070090200050007000000045700000100030001000068008500010090000400"
        )
        for (p in hard) {
            val t0 = System.nanoTime()
            val result = SudokuSolver.solve(grid(p))
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            assertTrue("no solution for $p -> $result", result is SolveResult.Solved)
            assertNull("invalid solution for $p", SudokuSolver.validate((result as SolveResult.Solved).solution))
            // The node cap is 2M; the worst published board needs ~58k nodes, so this is a
            // generous bound that still catches a pathological slowdown.
            assertTrue("$p took $ms ms", ms < 2000.0)
        }
    }

    @Test
    fun solvesClassicPuzzle() {
        val result = SudokuSolver.solve(grid(classic))
        assertTrue("expected a solution, got $result", result is SolveResult.Solved)
        assertArrayEquals(grid(classicSolution), (result as SolveResult.Solved).solution)
    }

    @Test
    fun solutionIsAlwaysValid() {
        val solved = (SudokuSolver.solve(grid(classic)) as SolveResult.Solved).solution
        assertNull("solution has duplicates", SudokuSolver.validate(solved))
        assertTrue(solved.all { it in 1..9 })
    }

    @Test
    fun fillsEmptyBoard() {
        val result = SudokuSolver.solve(grid("0".repeat(81)))
        assertTrue(result is SolveResult.Solved)
        val solved = (result as SolveResult.Solved).solution
        assertTrue(solved.none { it == 0 })
        assertNull(SudokuSolver.validate(solved))
    }

    @Test
    fun reportsDuplicateInRow() {
        val result = SudokuSolver.solve(grid("11000000" + "0".repeat(73)))
        assertTrue(result is SolveResult.Contradiction)
        assertTrue((result as SolveResult.Contradiction).message.contains("row 1"))
    }

    @Test
    fun reportsDuplicateInColumn() {
        val result = SudokuSolver.solve(grid("100000000100000000" + "0".repeat(63)))
        assertTrue(result is SolveResult.Contradiction)
        assertTrue((result as SolveResult.Contradiction).message.contains("column 1"))
    }

    @Test
    fun reportsDuplicateInBox() {
        val result = SudokuSolver.solve(grid("100000000010000000" + "0".repeat(63)))
        assertTrue(result is SolveResult.Contradiction)
        assertTrue((result as SolveResult.Contradiction).message.contains("box 1"))
    }

    @Test
    fun reportsOutOfRangeDigit() {
        val cells = grid("0".repeat(81))
        cells[5] = 12
        val conflict = SudokuSolver.validate(cells)
        assertNotNull(conflict)
        assertEquals(5, conflict!!.index)
    }

    @Test
    fun unsolvableGridIsNotAContradiction() {
        assertNull(SudokuSolver.validate(grid(unsolvable)))
        assertEquals(SolveResult.NoSolution, SudokuSolver.solve(grid(unsolvable)))
        assertEquals(0, SudokuSolver.countSolutions(grid(unsolvable), 2))
    }

    @Test
    fun countsSolutions() {
        assertEquals(1, SudokuSolver.countSolutions(grid(classic), 3))
        assertEquals(2, SudokuSolver.countSolutions(grid("0".repeat(81)), 2))
    }

    @Test
    fun detectsAnswersToShow() {
        val solved = (SudokuSolver.solve(grid(classic)) as SolveResult.Solved).solution
        assertTrue(SudokuSolver.hasAnswers(grid(classic), solved))
        assertTrue(!SudokuSolver.hasAnswers(solved, solved))
    }

    @Test
    fun solvesQuickly() {
        val g = grid(classic)
        val start = System.nanoTime()
        repeat(20) { SudokuSolver.solve(g) }
        val perSolveMs = (System.nanoTime() - start) / 1e6 / 20.0
        assertTrue("solver too slow: $perSolveMs ms", perSolveMs < 50.0)
    }
}
