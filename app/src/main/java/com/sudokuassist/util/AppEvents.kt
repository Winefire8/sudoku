package com.sudokuassist.util

import com.sudokuassist.vision.RecognizedGrid

/**
 * In-process event bus between the capture service and the overlay service.
 *
 * Both services live in the same process, so a simple thread-safe singleton is
 * enough — and it keeps the app dependency free.
 */
object AppEvents {

    /** State published by the capture service after each analysis run. */
    sealed class Update {
        data class Working(val message: String) : Update()

        /**
         * Nothing to do (no board on screen, or the same picture as before). Purely
         * informational: it must not raise an error banner or open the editor.
         */
        data class Idle(val message: String) : Update()
        data class GridFound(val grid: RecognizedGrid, val solutions: IntArray, val quality: Float) : Update()
        data class Failure(val reason: Reason, val message: String, val grid: RecognizedGrid? = null) : Update()
    }

    enum class Reason {
        NO_GRID,           // nothing that looks like a Sudoku board
        NOTHING_RECOGNIZED,// board found, but no digits could be read
        CONTRADICTION,     // recognised digits conflict with each other
        NO_SOLUTION,       // puzzle is valid but has no completion
        ERROR              // unexpected (OpenCV / capture failure)
    }

    interface Listener {
        fun onUpdate(update: Update)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<Listener>()

    @Volatile
    var lastUpdate: Update? = null
        private set

    fun register(listener: Listener) {
        listeners.add(listener)
        lastUpdate?.let { listener.onUpdate(it) }
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    fun publish(update: Update) {
        lastUpdate = update
        listeners.forEach { it.onUpdate(update) }
    }

    fun clear() {
        lastUpdate = null
    }
}
