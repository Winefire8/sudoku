package com.sudokuassist.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks whether a MediaProjection session is alive and builds the consent intent.
 *
 * Android grants the projection token once per consent dialog, so the session is started
 * right after the dialog and kept alive while the assistant is on screen. The flag here is
 * only a hint for the UI — the authoritative state is the running capture service, which
 * clears the flag when the projection is stopped by the system.
 */
object CaptureLauncher {

    private val active = AtomicBoolean(false)

    /** Set once the current board has been solved (used to avoid nagging re-consent). */
    private val solved = AtomicBoolean(false)

    fun markActive(value: Boolean) {
        active.set(value)
        if (!value) solved.set(false)
    }

    fun hasSession(): Boolean = active.get()

    fun markSolved() {
        solved.set(true)
    }

    fun hasSolved(): Boolean = solved.get()

    fun consentIntent(context: Context): Intent {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mpm.createScreenCaptureIntent()
    }
}
