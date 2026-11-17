package com.sudokuassist.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Tiny typed wrapper around SharedPreferences — no analytics, no network.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("sudoku_assist", Context.MODE_PRIVATE)

    var autoRefresh: Boolean
        get() = sp.getBoolean(KEY_AUTO, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO, value).apply()

    var showGivens: Boolean
        get() = sp.getBoolean(KEY_SHOW_GIVENS, false)
        set(value) = sp.edit().putBoolean(KEY_SHOW_GIVENS, value).apply()

    var useMlKit: Boolean
        get() = sp.getBoolean(KEY_MLKIT, true)
        set(value) = sp.edit().putBoolean(KEY_MLKIT, value).apply()

    /** Answer font size as a fraction of the cell size. */
    var answerScale: Float
        get() = sp.getFloat(KEY_SCALE, 0.62f).coerceIn(MIN_ANSWER_SCALE, MAX_ANSWER_SCALE)
        set(value) = sp.edit().putFloat(KEY_SCALE, value.coerceIn(MIN_ANSWER_SCALE, MAX_ANSWER_SCALE)).apply()

    /** Floating button position, -1 means "auto". */
    var buttonX: Int
        get() = sp.getInt(KEY_BTN_X, -1)
        set(value) = sp.edit().putInt(KEY_BTN_X, value).apply()

    var buttonY: Int
        get() = sp.getInt(KEY_BTN_Y, -1)
        set(value) = sp.edit().putInt(KEY_BTN_Y, value).apply()

    /** Last recognised grid, 81 comma separated digits, for quick re-use after a restart. */
    var lastGrid: String?
        get() = sp.getString(KEY_LAST_GRID, null)
        set(value) = sp.edit().putString(KEY_LAST_GRID, value).apply()

    var lastResult: String?
        get() = sp.getString(KEY_LAST_RESULT, null)
        set(value) = sp.edit().putString(KEY_LAST_RESULT, value).apply()

    companion object {
        /** Must match the Slider range in activity_main.xml. */
        const val MIN_ANSWER_SCALE = 0.35f
        const val MAX_ANSWER_SCALE = 0.95f

        private const val KEY_AUTO = "auto_refresh"
        private const val KEY_SHOW_GIVENS = "show_givens"
        private const val KEY_MLKIT = "use_mlkit"
        private const val KEY_SCALE = "answer_scale"
        private const val KEY_BTN_X = "button_x"
        private const val KEY_BTN_Y = "button_y"
        private const val KEY_LAST_GRID = "last_grid"
        private const val KEY_LAST_RESULT = "last_result"
    }
}
