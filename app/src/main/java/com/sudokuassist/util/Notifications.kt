package com.sudokuassist.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sudokuassist.MainActivity
import com.sudokuassist.R

/**
 * Notification helpers for the two foreground services.
 */
object Notifications {

    const val CHANNEL_CAPTURE = "capture"
    const val CHANNEL_OVERLAY = "overlay"

    const val ID_CAPTURE = 1001
    const val ID_OVERLAY = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                context.getString(R.string.notif_channel_capture),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_OVERLAY,
                context.getString(R.string.notif_channel_overlay),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Stop action of a foreground service notification.
     *
     * The intent has to target the service class explicitly: a bare action with getService()
     * would never resolve to the service and the button would silently do nothing.
     */
    private fun stopIntent(
        context: Context,
        serviceClass: Class<out android.app.Service>,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, serviceClass).setAction(action)
        return PendingIntent.getService(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun build(
        context: Context,
        channel: String,
        title: String,
        text: String,
        serviceClass: Class<out android.app.Service>? = null,
        stopAction: String? = null,
        stopLabel: String? = null,
        requestCode: Int = 0
    ): Notification {
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_sudoku)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent(context))
        if (stopAction != null && stopLabel != null && serviceClass != null) {
            builder.addAction(
                0, stopLabel, stopIntent(context, serviceClass, stopAction, requestCode)
            )
        }
        return builder.build()
    }
}
