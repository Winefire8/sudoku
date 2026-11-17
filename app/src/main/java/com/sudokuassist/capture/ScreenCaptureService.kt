package com.sudokuassist.capture

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.sudokuassist.R
import com.sudokuassist.solver.SolveResult
import com.sudokuassist.solver.SudokuSolver
import com.sudokuassist.util.AppEvents
import com.sudokuassist.util.Notifications
import com.sudokuassist.util.Prefs
import com.sudokuassist.vision.RecognizedGrid
import com.sudokuassist.vision.SudokuRecognizer

/**
 * Foreground service that owns the MediaProjection session.
 *
 * Flow: ACTION_START (with the one-shot projection token) → capture a frame →
 * recognise (OpenCV + ML Kit) → solve → publish to the overlay via [AppEvents].
 *
 * The service never keeps more than one screenshot and stops itself when the overlay
 * service goes away.
 */
class ScreenCaptureService : Service(), ScreenCaptureManager.Listener {

    private lateinit var prefs: Prefs
    private lateinit var captureManager: ScreenCaptureManager
    private var recognizer: SudokuRecognizer? = null
    private var recognizerUseMlKit: Boolean? = null
    private var workThread: HandlerThread? = null
    private var workHandler: Handler? = null
    private var projection: MediaProjection? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var analyzing = false
    /** A capture was requested while the previous one was still running. */
    private var pendingCapture = false
    /** True while an explicit user tap is being served (forces a fresh analysis). */
    private var userRequestedCapture = false
    private var autoRefresh = true
    private var lastFrameSignature: String? = null
    private var lastAnalysisAt = 0L

    private val autoTick = object : Runnable {
        override fun run() {
            if (autoRefresh && captureManager.isActive && !analyzing &&
                System.currentTimeMillis() - lastAnalysisAt >= AUTO_REFRESH_MS
            ) {
                mainHandler.post { captureFrame() }
            }
            mainHandler.postDelayed(this, AUTO_CHECK_MS)
        }
    }

    private fun scheduleAutoRefresh() {
        mainHandler.removeCallbacks(autoTick)
        if (autoRefresh) mainHandler.postDelayed(autoTick, AUTO_CHECK_MS)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        captureManager = ScreenCaptureManager()
        workThread = HandlerThread("sudoku-ocr").also { it.start() }
        workHandler = Handler(workThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode == Activity.RESULT_OK && data != null) {
                    startCapture(resultCode, data)
                } else {
                    publishError(getString(R.string.error_start_capture))
                    stopSelf()
                }
            }
            ACTION_CAPTURE -> {
                userRequestedCapture = true
                requestAnalysis()
            }
            ACTION_CAPTURE_MANUAL -> requestAnalysis()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafely() {
        Notifications.ensureChannels(this)
        val notification = Notifications.build(
            this,
            Notifications.CHANNEL_CAPTURE,
            getString(R.string.notif_capture_title),
            getString(R.string.notif_capture_text),
            serviceClass = ScreenCaptureService::class.java,
            stopAction = ACTION_STOP,
            stopLabel = getString(R.string.notif_stop),
            requestCode = 11
        )
        // The foreground service *type* must be declared on Android 14+, and the service
        // must be in the foreground before getMediaProjection() is called.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    Notifications.ID_CAPTURE,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(Notifications.ID_CAPTURE, notification)
            }
        }.onFailure { Log.e(TAG, "startForeground failed", it) }
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        captureManager.stop()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val mp = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "MediaProjection failed", t)
            null
        }
        if (mp == null) {
            publishError(getString(R.string.error_start_capture))
            stopSelf()
            return
        }
        projection = mp
        // Android 14 requires a registered callback before the first frame is produced.
        runCatching {
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "projection stopped by the system")
                    CaptureLauncher.markActive(false)
                    stopSelf()
                }
            }, mainHandler)
        }
        val metrics = ScreenCaptureManager.screenMetrics(wm)
        captureManager.start(mp, metrics)
        if (!captureManager.isActive) {
            publishError(getString(R.string.error_start_capture))
            stopSelf()
            return
        }
        autoRefresh = prefs.autoRefresh
        scheduleAutoRefresh()
        // Read the screen right away so the first tap on the floating button is instant.
        if (pendingCapture) {
            pendingCapture = false
            requestAnalysis()
        }
    }

    // ------------------------------------------------------------------ analysis

    /** Requests one analysis pass; coalesces requests that arrive while busy. */
    private fun requestAnalysis() {
        if (!captureManager.isActive) {
            pendingCapture = true
            publishError(getString(R.string.error_no_capture))
            return
        }
        if (analyzing) {
            pendingCapture = true
            return
        }
        captureFrame()
    }

    private fun captureFrame() {
        if (analyzing) {
            pendingCapture = true
            return
        }
        analyzing = true
        AppEvents.publish(AppEvents.Update.Working(getString(R.string.status_reading)))
        captureManager.capture(this)
    }

    override fun onFrame(bitmap: Bitmap) {
        val manual = userRequestedCapture
        userRequestedCapture = false
        if (!manual) {
            val sig = signature(bitmap)
            if (sig == lastFrameSignature) {
                // Nothing changed on screen: keep the answers that are already shown.
                bitmap.recycle()
                finishAnalysis()
                return
            }
            lastFrameSignature = sig
        }
        lastAnalysisAt = System.currentTimeMillis()
        val handler = workHandler
        if (handler == null) {
            bitmap.recycle()
            finishAnalysis()
            return
        }
        handler.post {
            try {
                process(bitmap)
            } catch (t: Throwable) {
                Log.e(TAG, "processing failed", t)
                if (!bitmap.isRecycled) bitmap.recycle()
                publishError(t.message ?: getString(R.string.error_no_grid))
            } finally {
                mainHandler.post { finishAnalysis() }
            }
        }
    }

    /** Clears the busy flag and runs a deferred capture, if one was requested. */
    private fun finishAnalysis() {
        analyzing = false
        if (pendingCapture) {
            pendingCapture = false
            if (captureManager.isActive) captureFrame()
        }
    }

    private fun process(bitmap: Bitmap) {
        // The ML Kit toggle can change between runs: rebuild the recogniser when it does.
        val useMlKit = prefs.useMlKit
        if (recognizerUseMlKit != useMlKit) {
            recognizer?.close()
            recognizer = null
            recognizerUseMlKit = useMlKit
        }
        val rec = recognizer ?: SudokuRecognizer(useMlKit = useMlKit).also { recognizer = it }
        val output = try {
            rec.recognize(bitmap)
        } catch (t: Throwable) {
            Log.e(TAG, "recognizer failed", t)
            null
        }
        if (output == null) {
            bitmap.recycle()
            publishFailure(AppEvents.Reason.NO_GRID, getString(R.string.error_no_grid), null)
            return
        }
        val grid: RecognizedGrid = output.grid
        if (grid.givensCount() == 0) {
            // The overlay keeps a downscaled copy for the manual editor.
            publishFailure(
                AppEvents.Reason.NOTHING_RECOGNIZED,
                getString(R.string.error_nothing_recognized),
                grid
            )
            return
        }
        when (val result = SudokuSolver.solve(grid.cells)) {
            is SolveResult.Solved -> {
                if (SudokuSolver.hasAnswers(grid.cells, result.solution)) {
                    AppEvents.publish(AppEvents.Update.GridFound(grid, result.solution, grid.quality))
                    grid.recycleScreenshot()
                    CaptureLauncher.markSolved()
                } else {
                    publishFailure(
                        AppEvents.Reason.CONTRADICTION,
                        getString(R.string.error_already_solved),
                        grid
                    )
                }
            }
            is SolveResult.Contradiction -> {
                publishFailure(
                    AppEvents.Reason.CONTRADICTION,
                    getString(R.string.error_unsolvable) + " (" + result.message + ")",
                    grid
                )
                grid.recycleScreenshot()
            }
            SolveResult.NoSolution -> {
                publishFailure(
                    AppEvents.Reason.NO_SOLUTION,
                    getString(R.string.error_no_solution),
                    grid
                )
                grid.recycleScreenshot()
            }
        }
    }

    private fun publishFailure(reason: AppEvents.Reason, message: String, grid: RecognizedGrid?) {
        AppEvents.publish(AppEvents.Update.Failure(reason, message, grid))
    }

    private fun publishError(message: String) {
        publishFailure(AppEvents.Reason.ERROR, message, null)
    }

    /**
     * Cheap “did the screen change?” signature: 12x24 thumbnail of the luminance.
     * Coarse enough to ignore small animations, sensitive enough for a new puzzle.
     */
    private fun signature(bitmap: Bitmap): String {
        val w = 12
        val h = 24
        val small = Bitmap.createScaledBitmap(bitmap, w, h, false)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        val sb = StringBuilder(w * h)
        for (p in px) {
            val lum = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            sb.append(lum / 32) // 8 luminance levels
        }
        return sb.toString()
    }

    override fun onError(t: Throwable) {
        Log.w(TAG, "capture error", t)
        publishError(t.message ?: getString(R.string.error_no_capture))
        finishAnalysis()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoTick)
        captureManager.stop()
        projection = null
        recognizer?.close()
        recognizer = null
        workThread?.quitSafely()
        workThread = null
        workHandler = null
        CaptureLauncher.markActive(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = CaptureBinder(this)

    /** Called through [CaptureBinder] when the user changes the settings. */
    fun onPreferencesChanged() {
        autoRefresh = prefs.autoRefresh
        scheduleAutoRefresh()
        if (autoRefresh) mainHandler.post { captureFrame() }
    }

    fun setAutoRefresh(enabled: Boolean) {
        autoRefresh = enabled
        scheduleAutoRefresh()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"

        /** Minimum time between two automatic analyses. */
        private const val AUTO_REFRESH_MS = 2500L

        /** How often the screen signature is checked for changes. */
        private const val AUTO_CHECK_MS = 1200L

        const val ACTION_START = "com.sudokuassist.action.START_CAPTURE"
        const val ACTION_CAPTURE = "com.sudokuassist.action.CAPTURE"
        const val ACTION_CAPTURE_MANUAL = "com.sudokuassist.action.CAPTURE_MANUAL"
        const val ACTION_STOP = "com.sudokuassist.action.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }

        fun captureIntent(context: Context, manual: Boolean = false): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = if (manual) ACTION_CAPTURE_MANUAL else ACTION_CAPTURE
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }

        /** The projection session is alive (a token was accepted and a display exists). */
        fun isSessionAlive(): Boolean = CaptureLauncher.hasSession()
    }
}
