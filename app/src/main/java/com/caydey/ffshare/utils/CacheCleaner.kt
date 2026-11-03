package com.caydey.ffshare.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.caydey.ffshare.CacheCleanUpReceiver
import com.caydey.ffshare.extensions.mediaCacheDir
import timber.log.Timber
import java.io.File

/**
 * Helpers around the temporary compression cache (`cacheDir/media/<uuid>/<file>`),
 * used both by the manual "clear cache" setting and by the periodic cleanup receiver.
 */
object CacheCleaner {

    data class ClearResult(val deletedFiles: Int, val freedBytes: Long)

    /** Total size of the media cache in bytes. */
    fun cacheSize(context: Context): Long = directorySize(context.mediaCacheDir)

    /** Number of media files currently sitting in the cache. */
    fun cacheFileCount(context: Context): Int =
        context.mediaCacheDir.walkBottomUp().count { it.isFile }

    /** Deletes every cached output and cancels the periodic cleanup alarm. */
    fun clear(context: Context): ClearResult {
        val mediaDir = context.mediaCacheDir
        val freed = directorySize(mediaDir)
        var deleted = 0
        mediaDir.listFiles()?.forEach { child ->
            if (child.deleteRecursively()) deleted++
        }
        Timber.d("Cleared %d cache entries, freed %d bytes", deleted, freed)

        // nothing left to clean up, drop the repeating alarm as well
        val intent = Intent(context, CacheCleanUpReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        )
        (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(pendingIntent)

        return ClearResult(deleted, freed)
    }

    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }
}
