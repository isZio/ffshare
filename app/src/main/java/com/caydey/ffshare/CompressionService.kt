package com.caydey.ffshare

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.arthenica.ffmpegkit.FFmpegKit
import timber.log.Timber

/**
 * Keeps the process alive (as a foreground service) while ffmpeg is running, so leaving the
 * app no longer cancels a compression that is halfway done. The actual ffmpeg work still
 * happens in [HandleMediaActivity]; this service only anchors the process and owns the
 * ongoing notification.
 */
class CompressionService : Service() {

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // a new compression starts with a clean progress state
        Notifications.resetProgressThrottle()
        startForeground(Notifications.ONGOING_ID, Notifications.ongoing(this))
        return START_NOT_STICKY
    }

    /**
     * Swiping the app out of the recents screen destroys [HandleMediaActivity], and with it every
     * callback that could report or save the result. Finishing the session here is better than
     * leaving an orphaned ffmpeg process behind (or, worse, calling back into a dead activity).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.d("Task removed while a compression was running, cancelling it")
        runCatching { FFmpegKit.cancel() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CompressionService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CompressionService::class.java)) }
        }
    }
}
