package com.caydey.ffshare

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Notification channel + the two notifications this app posts (ongoing / finished). */
object Notifications {
    private const val CHANNEL_ID = "compression"
    const val ONGOING_ID = 1001
    private const val DONE_ID = 1002

    /** Tapped "cancel" in the ongoing notification. Handled by [CancelCompressionReceiver]. */
    const val ACTION_CANCEL = "com.caydey.ffshare.CANCEL_COMPRESSION"

    // ffmpeg reports statistics far more often than a notification needs to be rebuilt
    private const val PROGRESS_THROTTLE_MS = 800L
    private var lastProgressAt = 0L

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * The ongoing notification. [text] replaces the default sub title, [percent] drives the
     * progress bar when [showProgress] is true (video only, ffmpeg cannot report image progress).
     */
    fun ongoing(
        context: Context,
        text: String? = null,
        percent: Int = 0,
        showProgress: Boolean = false
    ): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_compressing_title))
            .setContentText(text ?: context.getString(R.string.notif_compressing_text))
            .apply {
                if (showProgress) {
                    setProgress(100, percent.coerceIn(0, 100), false)
                }
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
            .addAction(
                R.drawable.ic_stop,
                context.getString(R.string.cancel_ffmpeg),
                cancelCompression(context)
            )
            .build()
    }

    /** Replaces the ongoing notification with a fresh progress state, throttled. */
    fun updateOngoing(
        context: Context,
        text: String,
        percent: Int,
        showProgress: Boolean = true
    ) {
        // the first update has to go through, otherwise the notification keeps its "starting" text
        val now = SystemClock.elapsedRealtime()
        if (lastProgressAt != 0L && now - lastProgressAt < PROGRESS_THROTTLE_MS) return
        lastProgressAt = now
        if (!canPost(context)) return
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(ONGOING_ID, ongoing(context, text, percent, showProgress))
        }
    }

    /** Resets the throttle so a new compression can update the notification immediately. */
    fun resetProgressThrottle() {
        lastProgressAt = 0L
    }

    /** Posted only when the app is in the background, so it never duplicates the result dialog. */
    fun notifyFinished(context: Context, text: String) {
        ensureChannel(context)
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_done_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(DONE_ID, notification) }
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Broadcast (not a service command) so the cancel works even if the activity is gone. */
    private fun cancelCompression(context: Context): PendingIntent {
        val intent = Intent(context, CancelCompressionReceiver::class.java).apply {
            action = ACTION_CANCEL
        }
        return PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
