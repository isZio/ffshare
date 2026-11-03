package com.caydey.ffshare

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.caydey.ffshare.extensions.parcelable
import com.caydey.ffshare.extensions.parcelableArrayList
import com.caydey.ffshare.utils.MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE
import com.caydey.ffshare.utils.MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE
import com.caydey.ffshare.utils.MediaCompressor
import com.caydey.ffshare.utils.MediaSaver
import com.caydey.ffshare.utils.Settings
import com.caydey.ffshare.utils.Utils
import timber.log.Timber


class HandleMediaActivity : AppCompatActivity() {
    // by lazy means load when variable is used, lazy-loading helps performance
    // also without it there is a null error for applicationContext
    private val mediaCompressor: MediaCompressor by lazy { MediaCompressor(applicationContext) }
    private val utils: Utils by lazy { Utils(applicationContext) }
    private val settings: Settings by lazy { Settings(applicationContext) }

    // files waiting for the storage permission before they can be copied to the gallery
    private var pendingSaveUris: ArrayList<Uri>? = null

    // upstream has a known flaky first run, one automatic retry usually goes through
    private var retriedCompression = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_handle_media)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (utils.isReadPermissionGranted) {
            onMediaReceive()
        } else {
            Timber.d("Requesting read permissions")
            utils.requestReadPermissions(this)
        }
    }

    override fun finish() {
        mediaCompressor.cancelAllOperations()
        scheduleCacheCleanup()
        super.finish()
    }

    override fun onStop() {
        mediaCompressor.cancelAllOperations()
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // first time running app user is requested to allow app to read external storage,
        // after clicking "allow" the app will continue handling media it was shared
        if (requestCode == MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE) {
            Timber.d("Read permissions granted, continuing...")
            onMediaReceive()
        }
        // API < 29 needs the write permission to copy the output into the gallery
        if (requestCode == MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE) {
            val uris = pendingSaveUris
            pendingSaveUris = null
            if (uris == null) return
            if (utils.isWritePermissionGranted) {
                Timber.d("Write permission granted, saving again")
                saveThenShare(uris)
            } else {
                Timber.d("Write permission denied, skipping the automatic save")
                Toast.makeText(this, getString(R.string.save_permission_needed), Toast.LENGTH_LONG).show()
                shareMedia(uris)
                finish()
            }
        }
    }

    private fun onMediaReceive() {
        // "intent" variable is the shared item
        val receivedMedia =
            when (intent.action) {
                Intent.ACTION_SEND -> arrayListOf(intent.parcelable(Intent.EXTRA_STREAM)!!)
                Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayList(Intent.EXTRA_STREAM)!!
                else -> ArrayList<Uri>()
            }

        // unable to get file from intent
        if (receivedMedia.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_no_uri_intent), Toast.LENGTH_LONG).show()
            Timber.d("No files found in shared intent")
            finish()
        } else {
            // callback
            mediaCompressor.compressFiles(this, receivedMedia) { compressedMedia, errorOutput ->
                when {
                    compressedMedia.isEmpty() && errorOutput != null -> {
                        if (!retriedCompression) {
                            retriedCompression = true
                            Timber.d("Compression failed, retrying once")
                            Toast.makeText(this, getString(R.string.retrying_compression), Toast.LENGTH_SHORT).show()
                            onMediaReceive()
                        } else {
                            showCompressionError(errorOutput)
                        }
                    }
                    compressedMedia.isEmpty() -> finish()
                    settings.autoSaveEnabled -> saveThenShare(compressedMedia)
                    else -> {
                        shareMedia(compressedMedia)
                        finish()
                    }
                }
            }
        }
    }

    /**
     * Shows the tail of the ffmpeg output instead of a bare toast, so a failure can be reported
     * (or understood) without pulling the whole log database.
     */
    private fun showCompressionError(output: String) {
        val tail = output.trim().lines().takeLast(25).joinToString("\n").ifBlank { getString(R.string.ffmpeg_error) }
        AlertDialog.Builder(this)
            .setTitle(R.string.ffmpeg_error)
            .setMessage(tail)
            .setPositiveButton(R.string.error_dialog_retry) { _, _ -> onMediaReceive() }
            .setNeutralButton(R.string.error_dialog_copy) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ffmpeg", output))
                Toast.makeText(this, getString(R.string.log_copied_toast), Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton(R.string.close) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    /**
     * Copies the compressed files to a persistent location (gallery or the folder picked in the
     * settings) and opens the share sheet afterwards. The copy has to happen before sharing
     * because the share uri points to the temporary cache, and it must not replace it.
     */
    private fun saveThenShare(compressedMedia: ArrayList<Uri>) {
        Toast.makeText(this, getString(R.string.save_in_progress), Toast.LENGTH_SHORT).show()
        Thread {
            val result = MediaSaver.saveAll(applicationContext, compressedMedia, settings.autoSaveTreeUri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result.needsStoragePermission) {
                    pendingSaveUris = compressedMedia
                    utils.requestWritePermissions(this)
                    return@runOnUiThread
                }
                val message = when {
                    result.isSuccess -> getString(R.string.save_success_toast, result.savedCount, result.location)
                    result.savedCount > 0 -> getString(R.string.save_partial_toast, result.savedCount, result.failedCount)
                    else -> getString(R.string.save_failed_toast)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                shareMedia(compressedMedia)
                finish()
            }
        }.start()
    }

    private fun shareMedia(mediaUris: ArrayList<Uri>) {
        val shareIntent = Intent()

        // temp permissions for other app to view file
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // add compressed media files
        if (mediaUris.size == 1) {
            Timber.d("Creating share intent for single item")
            shareIntent.action = Intent.ACTION_SEND
            shareIntent.putExtra(Intent.EXTRA_STREAM, mediaUris[0])
        } else {
            Timber.d("Creating share intent for multiple items")
            shareIntent.action = Intent.ACTION_SEND_MULTIPLE
            shareIntent.putExtra(Intent.EXTRA_STREAM, mediaUris)
        }

        // set mime for each file
        mediaUris.forEach { mediaUri ->
            shareIntent.setDataAndType(mediaUri, contentResolver.getType(mediaUri))
        }

        val chooser = Intent.createChooser(shareIntent, "media")
        startActivity(chooser)
    }

    private fun scheduleCacheCleanup() {
        Timber.d("Scheduling cleanup alarm")
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(applicationContext, CacheCleanUpReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)

        // every 12 hours clear cache
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime(),
            AlarmManager.INTERVAL_HALF_DAY,
            pendingIntent
        )
    }
}
