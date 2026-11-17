package com.sudokuassist.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.sudokuassist.util.Prefs

/** Binder exposed by [ScreenCaptureService] to push configuration updates. */
class CaptureBinder(private val service: ScreenCaptureService) : android.os.Binder() {

    fun onPreferencesChanged() = service.onPreferencesChanged()

    /** Enables / disables the auto-refresh loop (screen change detection). */
    fun setAutoRefresh(enabled: Boolean) = service.setAutoRefresh(enabled)
}

/**
 * Binds to [ScreenCaptureService] so the UI can push configuration changes to the running
 * session.
 *
 * Two things matter here:
 *  - it must never *create* the service. `bindService` with `BIND_AUTO_CREATE` would start
 *    the capture service without a MediaProjection token, so a binding is only attempted
 *    while [CaptureLauncher.hasSession] reports a live session;
 *  - the binding is reference counted: the settings activity and the overlay both use it,
 *    and whichever leaves first must not tear down the other's connection.
 */
object CaptureConnection {

    private val lock = Any()
    private var binder: CaptureBinder? = null
    private var connection: ServiceConnection? = null
    private var refCount = 0

    /** @return true when a session exists and a binding was requested. */
    fun bind(context: Context): Boolean {
        if (!CaptureLauncher.hasSession()) return false
        synchronized(lock) {
            refCount++
            if (connection != null) return true
            val app = context.applicationContext
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                    binder = b as? CaptureBinder
                    // The service may have been started with older settings; push the
                    // stored ones as soon as the connection is up.
                    val stored = Prefs(app)
                    runCatching {
                        binder?.setAutoRefresh(stored.autoRefresh)
                        binder?.onPreferencesChanged()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    binder = null
                }
            }
            connection = conn
            val ok = runCatching {
                context.applicationContext.bindService(
                    Intent(context, ScreenCaptureService::class.java),
                    conn,
                    0   // no BIND_AUTO_CREATE: only ever attach to an already running service
                )
            }.getOrDefault(false)
            if (!ok) {
                connection = null
                refCount = (refCount - 1).coerceAtLeast(0)
            }
            return ok
        }
    }

    fun unbind(context: Context) {
        synchronized(lock) {
            if (refCount > 0) refCount--
            if (refCount > 0) return
            connection?.let { runCatching { context.applicationContext.unbindService(it) } }
            connection = null
            binder = null
        }
    }

    /** Tells the running capture service that preferences changed. */
    fun preferencesChanged() {
        val prefs = binder ?: return
        runCatching { prefs.onPreferencesChanged() }
    }

    /** Applies the stored preferences to the running service. */
    fun applyPrefs(context: Context) {
        val prefs = Prefs(context)
        val b = binder ?: return
        runCatching {
            b.setAutoRefresh(prefs.autoRefresh)
            b.onPreferencesChanged()
        }
    }

    fun isBound(): Boolean = binder != null
}
