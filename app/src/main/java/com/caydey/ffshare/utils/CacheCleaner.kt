package com.caydey.ffshare.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.caydey.ffshare.CacheCleanUpReceiver
import com.caydey.ffshare.extensions.mediaCacheDir
import timber.log.Timber
import java.io.File

/**
 * Helpers around the temporary compression cache (`cacheDir/media/<uuid>/<file>`).
 *
 * Cleanup uses a single one-shot alarm instead of a repeating wake-up: compressed files are
 * kept for [HOUR] after the last compression, then deleted. While anything is still younger
 * than an hour the receiver re-schedules a short retry.
 */
object CacheCleaner {

    private const val HOUR = 60 * 60 * 1_000L
    private const val RETRY_DELAY = 30 * 60 * 1_000L

    data class ClearResult(val deletedFiles: Int, val freedBytes: Long)

    /** Total size of the media cache in bytes. */
    fun cacheSize(context: Context): Long = directorySize(context.mediaCacheDir)

    /** Number of media files currently sitting in the cache. */
    fun cacheFileCount(context: Context): Int =
        context.mediaCacheDir.walkBottomUp().count { it.isFile }

    /** Deletes every cached output and cancels the pending cleanup alarm. */
    fun clear(context: Context): ClearResult {
        val mediaDir = context.mediaCacheDir
        val freed = directorySize(mediaDir)
        var deleted = 0
        mediaDir.listFiles()?.forEach { child ->
            if (child.deleteRecursively()) deleted++
        }
        Timber.d("Cleared %d cache entries, freed %d bytes", deleted, freed)
        cancelCleanup(context)
        return ClearResult(deleted, freed)
    }

    /** Schedules the one-shot cleanup alarm, by default one hour from now. */
    fun scheduleCleanup(context: Context, delayMs: Long = HOUR) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            // setAndAllowWhileIdle so the cleanup still runs in Doze (set() would be deferred)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                cleanupIntent(context)
            )
        }
        Timber.d("Scheduled cache cleanup in %d ms", delayMs)
    }

    fun cancelCleanup(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { alarmManager.cancel(cleanupIntent(context)) }
    }

    fun retryDelayMs(): Long = RETRY_DELAY

    private fun cleanupIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, CacheCleanUpReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }
}
