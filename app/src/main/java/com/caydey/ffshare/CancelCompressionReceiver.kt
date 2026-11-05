package com.caydey.ffshare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.arthenica.ffmpegkit.FFmpegKit
import timber.log.Timber

/**
 * Cancel action of the ongoing notification. The compression runs inside
 * [HandleMediaActivity], so cancelling from the notification bar means killing the ffmpeg
 * session; the activity then unwinds through its normal failure path and closes itself.
 */
class CancelCompressionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Notifications.ACTION_CANCEL) return
        Timber.d("Cancel requested from the ongoing notification")
        FFmpegKit.cancel()
        Toast.makeText(context, context.getString(R.string.ffmpeg_canceled), Toast.LENGTH_SHORT).show()
    }
}
