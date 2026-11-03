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
import com.caydey.ffshare.utils.FFmpegParamMaker
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

    // outcome waiting for the storage permission before it can be copied to the gallery
    private var pendingOutcome: MediaCompressor.CompressionOutcome? = null

    // upstream has a known flaky first run, one automatic retry usually goes through
    private var retriedCompression = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_handle_media)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // a new share starts with the encoder the user picked
        FFmpegParamMaker.forceSoftwareCodec = false

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
            val outcome = pendingOutcome
            pendingOutcome = null
            if (outcome == null) return
            if (utils.isWritePermissionGranted) {
                Timber.d("Write permission granted, saving again")
                saveThenShowResult(outcome)
            } else {
                Timber.d("Write permission denied, skipping the automatic save")
                Toast.makeText(this, getString(R.string.save_permission_needed), Toast.LENGTH_LONG).show()
                showResult(outcome, null)
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
            mediaCompressor.compressFiles(this, receivedMedia) { outcome ->
                // ffmpeg invokes this callback on its own worker thread, every UI call must be posted
                runOnUiThread {
                    val compressedMedia = outcome.uris
                    when {
                        compressedMedia.isEmpty() && outcome.errorOutput != null -> {
                            if (!retriedCompression) {
                                retriedCompression = true
                                // a hardware encoder that rejects the input gets one software retry
                                FFmpegParamMaker.forceSoftwareCodec = true
                                Timber.d("Compression failed, retrying once")
                                Toast.makeText(this, getString(R.string.retrying_compression), Toast.LENGTH_SHORT).show()
                                onMediaReceive()
                            } else {
                                showCompressionError(outcome.errorOutput)
                            }
                        }
                        compressedMedia.isEmpty() -> finish()
                        settings.autoSaveEnabled -> saveThenShowResult(outcome)
                        else -> showResult(outcome, null)
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
     * settings) and then shows the result summary. The copy must not replace the cache uri,
     * because that uri is still used when the user decides to share afterwards.
     */
    private fun saveThenShowResult(outcome: MediaCompressor.CompressionOutcome) {
        Toast.makeText(this, getString(R.string.save_in_progress), Toast.LENGTH_SHORT).show()
        Thread {
            val result = MediaSaver.saveAll(applicationContext, outcome.uris, settings.autoSaveTreeUri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result.needsStoragePermission) {
                    pendingOutcome = outcome
                    utils.requestWritePermissions(this)
                    return@runOnUiThread
                }
                val location = when {
                    result.isSuccess -> result.location
                    result.savedCount > 0 -> result.location
                    else -> null
                }
                if (result.isCompleteFailure) {
                    Toast.makeText(this, getString(R.string.save_failed_toast), Toast.LENGTH_LONG).show()
                }
                showResult(outcome, location)
            }
        }.start()
    }

    /** Result summary: size before/after, ratio and elapsed time. Sharing is opt-in. */
    private fun showResult(outcome: MediaCompressor.CompressionOutcome, savedLocation: String?) {
        val inputHuman = utils.bytesToHuman(outcome.inputBytes)
        val outputHuman = utils.bytesToHuman(outcome.outputBytes)
        val ratio = if (outcome.inputBytes > 0) {
            (1.0 - outcome.outputBytes.toDouble() / outcome.inputBytes) * 100
        } else {
            0.0
        }
        val lines = mutableListOf<String>()
        lines.add(
            if (ratio >= 0) {
                getString(R.string.result_size_line, inputHuman, outputHuman, ratio)
            } else {
                getString(R.string.result_size_grew_line, inputHuman, outputHuman, -ratio)
            }
        )
        lines.add(getString(R.string.result_elapsed_line, utils.millisToMicrowaveTime(outcome.elapsedMillis.toInt())))
        if (outcome.uris.size > 1) {
            lines.add(getString(R.string.result_files_line, outcome.uris.size))
        }
        if (!savedLocation.isNullOrBlank()) {
            lines.add(getString(R.string.result_saved_line, savedLocation))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.result_dialog_title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.result_share) { _, _ ->
                shareMedia(outcome.uris)
                finish()
            }
            .setNegativeButton(R.string.result_done) { _, _ -> finish() }
            .setCancelable(false)
            .show()
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
