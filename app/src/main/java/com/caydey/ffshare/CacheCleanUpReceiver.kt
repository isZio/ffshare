package com.caydey.ffshare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.caydey.ffshare.utils.CacheCleaner
import timber.log.Timber
import java.io.File

/**
 * One-shot cache cleanup: drops compressed files older than an hour and re-schedules itself
 * only while fresh files are still around. This replaces the old 12-hour repeating alarm.
 */
class CacheCleanUpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        /*
        Cache structure
        /media/
            UUID/custom-name.mp4
            UUID/UUID.jpg
         */
        Timber.d("Cleaning up cache")

        val mediaDir = File(context.cacheDir, "media")
        val cacheFiles = mediaDir.listFiles() ?: return

        for (cacheFile in cacheFiles) {
            // media files older than 1 hour are safe to delete
            if (System.currentTimeMillis() - cacheFile.lastModified() > HOUR) {
                Timber.d("Deleting '%s'", cacheFile)
                cacheFile.deleteRecursively()
            }
        }

        if (mediaDir.listFiles()?.isNotEmpty() == true) {
            // something is still fresh, check again later
            CacheCleaner.scheduleCleanup(context, CacheCleaner.retryDelayMs())
        } else {
            CacheCleaner.cancelCleanup(context)
        }
    }

    companion object {
        private const val HOUR = 60 * 60 * 1_000L
    }
}
