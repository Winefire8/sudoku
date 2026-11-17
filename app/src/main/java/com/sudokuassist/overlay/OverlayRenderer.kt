package com.sudokuassist.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Thin wrapper around [WindowManager] that owns the overlay windows.
 *
 * Two windows are used:
 *  - the **digit layer**: full-screen, non-touchable, [PixelFormat.TRANSLUCENT];
 *  - the **control panel**: a small draggable floating window with buttons.
 *
 * Keeping them apart means the touchable panel can be moved without affecting the
 * geometry of the answer layer (which is anchored to the screen origin).
 */
class OverlayRenderer(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var digitView: DigitOverlayView? = null
    private var panelView: View? = null

    val isShowingDigits: Boolean get() = digitView != null
    val isShowingPanel: Boolean get() = panelView != null

    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    /** Adds the transparent digit layer (or updates its size if the screen rotated). */
    fun attachDigitLayer(): DigitOverlayView {
        digitView?.let { view ->
            runCatching { wm.removeViewImmediate(view) }
        }
        val view = DigitOverlayView(context)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching { wm.addView(view, lp) }
        digitView = view
        return view
    }

    /** Adds the draggable control panel. */
    fun attachPanel(panel: View, x: Int, y: Int, dragListener: View.OnTouchListener) {
        detachPanel()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            baseFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        panel.setOnTouchListener(dragListener)
        runCatching { wm.addView(panel, lp) }
        panelView = panel
    }

    /** Moves the panel (drag). */
    fun updatePanelPosition(x: Int, y: Int) {
        val v = panelView ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = x
        lp.y = y
        runCatching { wm.updateViewLayout(v, lp) }
    }

    fun panelPosition(): Pair<Int, Int>? {
        val v = panelView ?: return null
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return null
        return lp.x to lp.y
    }

    fun panelSize(): Pair<Int, Int>? {
        val v = panelView ?: return null
        return v.width to v.height
    }

    fun detachPanel() {
        panelView?.let { runCatching { wm.removeViewImmediate(it) } }
        panelView = null
    }

    fun detachAll() {
        detachPanel()
        digitView?.let { runCatching { wm.removeViewImmediate(it) } }
        digitView = null
    }

    fun digitLayer(): DigitOverlayView? = digitView

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
