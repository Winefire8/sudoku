package com.sudokuassist.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the [MediaProjection] session and produces single screenshots on demand.
 *
 * Frames are pulled from an [ImageReader] backed [VirtualDisplay]. Only one frame is kept
 * in memory at a time — nothing is written to disk.
 *
 * A capture request always completes exactly once: either with a *new* frame (the usual
 * case), or — when the mirrored screen is static and the system has nothing new to send —
 * with the newest buffered frame, or with an error. This matters because a dropped frame
 * would otherwise leave the analysis flag stuck and stop the assistant from ever
 * refreshing again.
 */
class ScreenCaptureManager {

    interface Listener {
        fun onFrame(bitmap: Bitmap)
        fun onError(t: Throwable)
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val busy = AtomicBoolean(false)

    var screenWidth: Int = 0
        private set
    var screenHeight: Int = 0
        private set
    var densityDpi: Int = 0
        private set

    val isActive: Boolean get() = virtualDisplay != null

    /**
     * Starts a capture session.
     * @param metrics real screen metrics in pixels (use WindowManager.getMaximumWindowMetrics on API 30+)
     */
    fun start(mediaProjection: MediaProjection, metrics: DisplayMetrics) {
        stop()
        screenWidth = metrics.widthPixels.coerceAtLeast(1)
        screenHeight = metrics.heightPixels.coerceAtLeast(1)
        densityDpi = if (metrics.densityDpi > 0) metrics.densityDpi else 160
        projection = mediaProjection

        val t = HandlerThread("sudoku-capture").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        val reader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 3
        )
        imageReader = reader
        virtualDisplay = runCatching {
            mediaProjection.createVirtualDisplay(
                "sudoku-capture",
                screenWidth, screenHeight, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, h
            )
        }.getOrElse { e ->
            Log.e(TAG, "could not create the virtual display", e)
            reader.close()
            imageReader = null
            null
        }
    }

    /**
     * Captures one frame and delivers it to [listener].
     * The callback is invoked on the capture thread, exactly once per call.
     */
    fun capture(listener: Listener) {
        val reader = imageReader
        val h = handler
        if (reader == null || h == null || virtualDisplay == null) {
            listener.onError(IllegalStateException("capture session is not running"))
            return
        }
        if (!busy.compareAndSet(false, true)) {
            listener.onError(IllegalStateException("a capture is already running"))
            return
        }

        val delivered = AtomicBoolean(false)

        fun deliver(image: Image?, failure: Throwable?) {
            if (!delivered.compareAndSet(false, true)) {
                image?.close()
                return
            }
            reader.setOnImageAvailableListener(null, null)
            busy.set(false)
            if (image == null) {
                listener.onError(failure ?: IllegalStateException("no frame available"))
                return
            }
            try {
                listener.onFrame(toBitmap(image))
            } catch (t: Throwable) {
                listener.onError(t)
            } finally {
                image.close()
            }
        }

        reader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (t: Throwable) {
                null
            }
            if (image != null) deliver(image, null)
            // acquireLatestImage() returns null when another consumer took the buffer;
            // the timeout below then takes over.
        }, h)

        // The mirrored screen can be static for a long time; don't hang on it.
        h.postDelayed({
            if (!delivered.get()) {
                val latest = runCatching { reader.acquireLatestImage() }.getOrNull()
                if (latest != null) {
                    deliver(latest, null)
                } else {
                    deliver(null, IllegalStateException("no new frame arrived"))
                }
            }
        }, FRAME_TIMEOUT_MS)
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        // Bitmap with the padded row stride, then crop.
        val widthWithPadding = image.width + rowPadding / pixelStride
        val bmp = Bitmap.createBitmap(widthWithPadding, image.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buffer)
        if (widthWithPadding == image.width) return bmp
        val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
        if (cropped !== bmp) bmp.recycle()
        return cropped
    }

    fun stop() {
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        thread?.quitSafely()
        thread = null
        handler = null
        runCatching { projection?.stop() }
        projection = null
        busy.set(false)
    }

    companion object {
        private const val TAG = "ScreenCaptureManager"

        /** How long to wait for a fresh frame before falling back to the buffered one. */
        private const val FRAME_TIMEOUT_MS = 900L

        /** Real display metrics including system bars. */
        fun screenMetrics(windowManager: WindowManager): DisplayMetrics {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager.maximumWindowMetrics
                val bounds = metrics.bounds
                val dm = DisplayMetrics()
                dm.widthPixels = bounds.width()
                dm.heightPixels = bounds.height()
                dm.densityDpi = (metrics.density * 160f).toInt()
                dm
            } else {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(dm)
                dm
            }
        }
    }
}
