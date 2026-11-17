package com.sudokuassist.solver

/**
 * Result of a solve attempt.
 */
sealed class SolveResult {
    /** At least one solution exists; [solution] is the solved 81-cell grid. */
    data class Solved(val solution: IntArray, val steps: Int) : SolveResult()

    /** The givens already contradict each other (or the board is not fillable). */
    data class Contradiction(val row: Int, val col: Int, val message: String) : SolveResult()

    /** No valid completion of the givens exists. */
    object NoSolution : SolveResult()

}

/**
 * Backtracking Sudoku solver with validation.
 *
 * - [validate] checks the current grid for duplicate digits and returns the conflicting cell.
 * - [solve] returns the first solution, or a precise reason why none exists.
 * - [countSolutions] can be used to detect ambiguous boards (optional).
 *
 * The implementation is intentionally self-contained (no dependencies) so it can be
 * unit-tested on the JVM.
 */
object SudokuSolver {

    const val EMPTY = 0
    const val N = 9
    const val CELLS = 81

    /** A cell that contradicts a given digit. */
    data class Conflict(val index: Int, val value: Int, val reason: String)

    fun cellToRow(index: Int): Int = index / N
    fun cellToCol(index: Int): Int = index % N

    private fun boxStart(index: Int): Int {
        val r = index / N / 3 * 3
        val c = index % N / 3 * 3
        return r * N + c
    }

    /**
     * Checks the givens: every digit 1..9 may appear at most once per row, column and box.
     * @return the first conflict or null when the grid is consistent.
     */
    fun validate(cells: IntArray): Conflict? {
        require(cells.size == CELLS) { "grid must have 81 cells" }
        // rows
        for (r in 0 until N) {
            val seen = IntArray(10)
            for (c in 0 until N) {
                val v = cells[r * N + c]
                if (v == EMPTY) continue
                if (v !in 1..9) return Conflict(r * N + c, v, "Digit out of range")
                if (seen[v] != 0) return Conflict(r * N + c, v, "Digit $v repeats in row ${r + 1}")
                seen[v] = 1
            }
        }
        // columns
        for (c in 0 until N) {
            val seen = IntArray(10)
            for (r in 0 until N) {
                val v = cells[r * N + c]
                if (v == EMPTY) continue
                if (seen[v] != 0) return Conflict(r * N + c, v, "Digit $v repeats in column ${c + 1}")
                seen[v] = 1
            }
        }
        // boxes
        for (br in 0 until 3) {
            for (bc in 0 until 3) {
                val seen = IntArray(10)
                for (dr in 0 until 3) {
                    for (dc in 0 until 3) {
                        val idx = (br * 3 + dr) * N + (bc * 3 + dc)
                        val v = cells[idx]
                        if (v == EMPTY) continue
                        if (seen[v] != 0) return Conflict(idx, v, "Digit $v repeats in box ${br * 3 + bc + 1}")
                        seen[v] = 1
                    }
                }
            }
        }
        return null
    }

    /** Candidate bitmask per empty cell (bit d set means digit d is allowed). */
    private fun candidates(board: IntArray, index: Int): Int {
        val r = index / N
        val c = index % N
        var used = 0
        for (k in 0 until N) {
            used = used or (1 shl board[r * N + k])
            used = used or (1 shl board[k * N + c])
        }
        val bs = boxStart(index)
        for (dr in 0 until 3) for (dc in 0 until 3) used = used or (1 shl board[bs + dr * N + dc])
        var mask = 0
        for (d in 1..9) if (used and (1 shl d) == 0) mask = mask or (1 shl d)
        return mask
    }

    private fun bitCount(mask: Int): Int = Integer.bitCount(mask)

    /**
     * Solves the puzzle.
     * @param givens 81 cells, 0 = empty, 1..9 = given digit.
     */
    fun solve(givens: IntArray): SolveResult {
        val conflict = validate(givens)
        if (conflict != null) {
            return SolveResult.Contradiction(
                cellToRow(conflict.index), cellToCol(conflict.index), conflict.reason
            )
        }
        val board = givens.copyOf()
        var steps = 0
        // Iterative-ish recursive search with MRV (minimum remaining values) heuristics.
        var counter = 0
        val solution = IntArray(CELLS)
        var found = false

        fun place(index: Int): Boolean {
            counter++
            if (counter > 2_000_000) return false // safety valve
            var best = -1
            var bestMask = 0
            var bestCount = 10
            for (i in 0 until CELLS) {
                if (board[i] != EMPTY) continue
                val mask = candidates(board, i)
                val cnt = bitCount(mask)
                if (cnt == 0) return false
                if (cnt < bestCount) {
                    best = i; bestMask = mask; bestCount = cnt
                    if (cnt == 1) break
                }
            }
            if (best == -1) {
                board.copyInto(solution)
                found = true
                return true
            }
            var mask = bestMask
            while (mask != 0) {
                val d = Integer.numberOfTrailingZeros(mask)
                mask = mask and (mask - 1)
                board[best] = d
                steps++
                if (place(best)) return true
                board[best] = EMPTY
            }
            return false
        }

        // avoid stack overflows on pathological input: depth is at most 81, fine
        place(0)
        return if (found) SolveResult.Solved(solution, steps) else SolveResult.NoSolution
    }

    /**
     * Counts solutions up to [limit] — used to warn about ambiguous puzzles.
     */
    fun countSolutions(givens: IntArray, limit: Int = 2): Int {
        if (validate(givens) != null) return 0
        val board = givens.copyOf()
        var count = 0

        fun search(): Boolean {
            var best = -1
            var bestMask = 0
            var bestCount = 10
            for (i in 0 until CELLS) {
                if (board[i] != EMPTY) continue
                val mask = candidates(board, i)
                val cnt = bitCount(mask)
                if (cnt == 0) return false
                if (cnt < bestCount) { best = i; bestMask = mask; bestCount = cnt; if (cnt == 1) break }
            }
            if (best == -1) {
                count++
                return count >= limit
            }
            var mask = bestMask
            while (mask != 0) {
                val d = Integer.numberOfTrailingZeros(mask)
                mask = mask and (mask - 1)
                board[best] = d
                if (search()) return true
                board[best] = EMPTY
            }
            return false
        }
        search()
        return count
    }

    /** True when [given] differs from [solved] in at least one cell (i.e. we have answers to show). */
    fun hasAnswers(given: IntArray, solved: IntArray): Boolean {
        for (i in 0 until CELLS) if (given[i] == EMPTY && solved[i] != EMPTY) return true
        return false
    }
}
