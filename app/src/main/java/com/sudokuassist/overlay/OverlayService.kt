package com.sudokuassist.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.sudokuassist.MainActivity
import com.sudokuassist.R
import com.sudokuassist.capture.CaptureLauncher
import com.sudokuassist.capture.ScreenCaptureService
import com.sudokuassist.solver.SolveResult
import com.sudokuassist.solver.SudokuSolver
import com.sudokuassist.util.AppEvents
import com.sudokuassist.util.Notifications
import com.sudokuassist.util.Prefs
import com.sudokuassist.vision.RecognizedGrid
import com.sudokuassist.capture.CaptureConnection
import kotlin.math.abs

/**
 * Foreground service that shows the floating assistant:
 *
 *  - a draggable round button (the only touchable window),
 *  - a control panel with “Read grid / Edit / Solve / Close”,
 *  - the transparent [DigitOverlayView] that paints the answers over the cells,
 *  - the manual correction dialog (also an overlay window, so it is usable on top of
 *    other apps without leaving them).
 */
class OverlayService : Service(), AppEvents.Listener {

    private lateinit var prefs: Prefs
    private lateinit var renderer: OverlayRenderer
    private val main = Handler(Looper.getMainLooper())

    private var buttonView: View? = null
    private var panelView: View? = null
    private var editorLayout: View? = null
    private var editorView: ManualEditView? = null

    private var currentGrid: RecognizedGrid? = null
    private var currentSolution: IntArray? = null
    private var lastMessage: String? = null
    private var lastMessageIsError = false
    private var panelVisible = false

    // button drag state
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        renderer = OverlayRenderer(this)
        startForegroundSafely()
        showFloatingButton()
        renderer.attachDigitLayer()
        CaptureConnection.bind(this)
        AppEvents.register(this)
        renderer.digitLayer()?.setState(
            DigitOverlayView.State(null, null, prefs.showGivens, prefs.answerScale, getString(R.string.status_running))
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE -> requestCapture()
            ACTION_CLOSE -> stopSelf()
            ACTION_EDIT -> openEditor()
            ACTION_SOLVE -> solveCurrent()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafely() {
        Notifications.ensureChannels(this)
        val notification = Notifications.build(
            this,
            Notifications.CHANNEL_OVERLAY,
            getString(R.string.notif_overlay_title),
            getString(R.string.notif_overlay_text),
            serviceClass = OverlayService::class.java,
            stopAction = ACTION_CLOSE,
            stopLabel = getString(R.string.notif_stop),
            requestCode = 21
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.ID_OVERLAY, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(Notifications.ID_OVERLAY, notification)
        }
    }

    // ------------------------------------------------------------------ floating button

    private fun showFloatingButton() {
        if (buttonView != null) return
        val size = (56 * resources.displayMetrics.density).toInt()
        val view = TextView(this).apply {
            text = "9"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 24f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = ContextCompat.getDrawable(this@OverlayService, R.drawable.float_button_background)
            contentDescription = getString(R.string.overlay_capture)
        }
        val lp = android.view.WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_PHONE,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val dm = resources.displayMetrics
            x = if (prefs.buttonX >= 0) prefs.buttonX else dm.widthPixels - size - (12 * dm.density).toInt()
            y = if (prefs.buttonY >= 0) prefs.buttonY else (dm.heightPixels * 0.35f).toInt()
        }
        view.setOnTouchListener { _, event -> handleButtonTouch(event, lp, size) }
        runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).addView(view, lp)
        }
        buttonView = view
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleButtonTouch(event: MotionEvent, lp: android.view.WindowManager.LayoutParams, size: Int): Boolean {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = lp.x
                startY = lp.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (abs(dx) > 12 || abs(dy) > 12)) dragging = true
                if (dragging) {
                    val dm = resources.displayMetrics
                    lp.x = (startX + dx).toInt().coerceIn(-size / 2, dm.widthPixels - size / 2)
                    lp.y = (startY + dy).toInt().coerceIn(-size / 2, dm.heightPixels - size / 2)
                    val v = buttonView ?: return true
                    runCatching { wm.updateViewLayout(v, lp) }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    prefs.buttonX = lp.x
                    prefs.buttonY = lp.y
                } else {
                    togglePanel()
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ control panel

    private fun togglePanel() {
        if (panelVisible) hidePanel() else showPanel()
    }

    private fun showPanel() {
        if (panelView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.panel_overlay, null)
        view.findViewById<Button>(R.id.btnCapture).setOnClickListener { requestCapture() }
        view.findViewById<Button>(R.id.btnEdit).setOnClickListener { openEditor() }
        view.findViewById<Button>(R.id.btnSolve).setOnClickListener { solveCurrent() }
        view.findViewById<Button>(R.id.btnClose).setOnClickListener {
            hidePanel()
            stopSelf()
        }
        val dm = resources.displayMetrics
        val x = ((prefs.buttonX.takeIf { it >= 0 } ?: (dm.widthPixels - 260 * dm.density).toInt()) - (220 * dm.density).toInt())
            .coerceIn(0, (dm.widthPixels - 200 * dm.density).toInt())
        val y = (prefs.buttonY.takeIf { it >= 0 } ?: (dm.heightPixels * 0.35f).toInt())
            .coerceIn(0, (dm.heightPixels - 150 * dm.density).toInt())
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_PHONE,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        runCatching { wm.addView(view, lp) }
        panelView = view
        panelVisible = true
    }

    private fun hidePanel() {
        panelView?.let {
            runCatching { (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).removeViewImmediate(it) }
        }
        panelView = null
        panelVisible = false
    }

    // ------------------------------------------------------------------ actions

    private fun requestCapture() {
        hidePanel()
        if (!CaptureLauncher.hasSession()) {
            // The projection token lives in the activity's consent dialog.
            toast(getString(R.string.error_no_capture))
            openCaptureSettings()
            return
        }
        updateDigitLayer(message = getString(R.string.status_reading), isError = false)
        val intent = ScreenCaptureService.captureIntent(this)
        runCatching { startService(intent) }
            .onFailure { toast(it.message ?: getString(R.string.error_start_capture)) }
    }

    /** Brings [MainActivity] to the front so the user can grant capture again. */
    private fun openCaptureSettings() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .setAction(MainActivity.ACTION_REQUEST_CAPTURE)
            )
        }
    }

    private fun solveCurrent() {
        val grid = currentGrid ?: run {
            toast(getString(R.string.error_no_grid))
            return
        }
        when (val r = SudokuSolver.solve(grid.cells)) {
            is SolveResult.Solved -> {
                currentSolution = r.solution
                updateDigitLayer(message = null, isError = false)
            }
            is SolveResult.Contradiction -> {
                updateDigitLayer(message = r.message, isError = true)
            }
            SolveResult.NoSolution -> updateDigitLayer(message = getString(R.string.error_no_solution), isError = true)
        }
    }

    private fun openEditor() {
        hidePanel()
        val grid = currentGrid ?: RecognizedGrid(
            IntArray(81), FloatArray(81), null, 0, 0, 0f
        )
        if (editorLayout != null) return
        val layout = LayoutInflater.from(this).inflate(R.layout.editor_overlay, null)
        val edit = layout.findViewById<ManualEditView>(R.id.editGrid)
        val screenshot = grid.screenshot
        edit.bind(screenshot, grid.geometry, grid.cells)
        edit.listener = object : ManualEditView.Listener {
            override fun onCellSelected(index: Int) {
                layout.findViewById<TextView>(R.id.editHint).text =
                    getString(R.string.editor_title) + "  (R${index / 9 + 1} C${index % 9 + 1})"
            }

            override fun onGridChanged(cells: IntArray) {
                // live preview while editing
            }
        }
        // digit pad
        val pad = layout.findViewById<LinearLayout>(R.id.editPad)
        for (i in 1..9) {
            val b = Button(this).apply {
                text = i.toString()
                textSize = 18f
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { edit.setDigit(i) }
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = (2 * resources.displayMetrics.density).toInt()
            pad.addView(b, lp)
        }
        val clear = layout.findViewById<Button>(R.id.btnEditClear)
        clear.setOnClickListener { edit.setDigit(0) }
        layout.findViewById<Button>(R.id.btnEditApply).setOnClickListener {
            applyEdited(edit.digits(), grid)
            closeEditor()
        }
        layout.findViewById<Button>(R.id.btnEditCancel).setOnClickListener { closeEditor() }

        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.92f).toInt()
        val h = (dm.heightPixels * 0.94f).toInt()
        val lp = android.view.WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_PHONE,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.CENTER
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val added = runCatching { wm.addView(layout, lp) }.isSuccess
        if (!added) {
            toast(getString(R.string.error_overlay_permission))
            return
        }
        editorLayout = layout
        editorView = edit
    }

    private fun closeEditor() {
        editorLayout?.let {
            runCatching { (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).removeViewImmediate(it) }
        }
        editorLayout = null
        editorView = null
    }

    private fun applyEdited(cells: IntArray, old: RecognizedGrid) {
        val conflict = SudokuSolver.validate(cells)
        val grid = RecognizedGrid(
            cells = cells,
            confidences = FloatArray(81),
            geometry = old.geometry,
            imageWidth = old.imageWidth,
            imageHeight = old.imageHeight,
            quality = old.quality,
            screenshot = old.screenshot
        )
        currentGrid = grid
        if (conflict != null) {
            currentSolution = null
            updateDigitLayer(message = conflict.reason, isError = true)
            savePrefs(cells, null)
            return
        }
        when (val r = SudokuSolver.solve(cells)) {
            is SolveResult.Solved -> {
                currentSolution = r.solution
                updateDigitLayer(message = null, isError = false)
                savePrefs(cells, r.solution)
            }
            is SolveResult.Contradiction -> updateDigitLayer(message = r.message, isError = true)
            SolveResult.NoSolution -> updateDigitLayer(message = getString(R.string.error_no_solution), isError = true)
        }
    }

    private fun savePrefs(cells: IntArray, solution: IntArray?) {
        prefs.lastGrid = cells.joinToString(",")
        prefs.lastResult = solution?.joinToString(",")
    }

    // ------------------------------------------------------------------ rendering

    private fun updateDigitLayer(message: String? = lastMessage, isError: Boolean = lastMessageIsError) {
        lastMessage = message
        lastMessageIsError = isError
        val view = renderer.digitLayer() ?: return
        view.setState(
            DigitOverlayView.State(
                grid = currentGrid,
                solutions = currentSolution,
                showGivens = prefs.showGivens,
                answerScale = prefs.answerScale,
                message = message,
                messageIsError = isError
            )
        )
    }

    override fun onUpdate(update: AppEvents.Update) {
        main.post {
            when (update) {
                is AppEvents.Update.Working -> updateDigitLayer(update.message, false)
                is AppEvents.Update.GridFound -> {
                    replaceGrid(update.grid)
                    currentSolution = update.solutions
                    updateDigitLayer(null, false)
                    savePrefs(update.grid.cells, update.solutions)
                }
                is AppEvents.Update.Failure -> {
                    update.grid?.let { replaceGrid(it) }
                    // auto-open the editor when recognition failed on a real board
                    if (update.reason == AppEvents.Reason.NOTHING_RECOGNIZED ||
                        update.reason == AppEvents.Reason.CONTRADICTION
                    ) {
                        main.postDelayed({ if (editorLayout == null) openEditor() }, 350)
                    }
                    currentSolution = null
                    updateDigitLayer(update.message, true)
                    toast(update.message)
                }
            }
        }
    }

    /**
     * Installs a new grid while keeping only a downscaled screenshot for the manual
     * editor. The full-screen bitmap belongs to the capture service and is recycled right
     * after processing, so it must never be referenced here.
     */
    private fun replaceGrid(grid: RecognizedGrid) {
        val thumb = grid.copyWithThumbnail()
        val previous = currentGrid
        currentGrid = thumb
        if (previous != null && previous.screenshot !== thumb.screenshot) {
            previous.recycleScreenshot()
        }
    }

    private fun toast(text: String) {
        runCatching { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onDestroy() {
        AppEvents.unregister(this)
        closeEditor()
        hidePanel()
        buttonView?.let {
            runCatching { (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).removeViewImmediate(it) }
        }
        buttonView = null
        renderer.detachAll()
        CaptureConnection.unbind(this)
        currentGrid?.recycleScreenshot()
        currentGrid = null
        currentSolution = null
        AppEvents.clear()
        runCatching { stopService(Intent(this, ScreenCaptureService::class.java)) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CAPTURE = "com.sudokuassist.action.OVERLAY_CAPTURE"
        const val ACTION_CLOSE = "com.sudokuassist.action.OVERLAY_CLOSE"
        const val ACTION_EDIT = "com.sudokuassist.action.OVERLAY_EDIT"
        const val ACTION_SOLVE = "com.sudokuassist.action.OVERLAY_SOLVE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
