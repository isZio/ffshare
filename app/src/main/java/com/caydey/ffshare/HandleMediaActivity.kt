package com.caydey.ffshare

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.caydey.ffshare.extensions.parcelable
import com.caydey.ffshare.extensions.parcelableArrayList
import com.caydey.ffshare.utils.CacheCleaner
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

    // whether the activity is visible, used to decide if a completion notification is needed
    private var isInForeground = false

    private companion object {
        const val REQUEST_NOTIFICATIONS = 300
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_handle_media)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // a new share starts with the encoder the user picked
        FFmpegParamMaker.forceSoftwareCodec = false

        // API 33+ needs a runtime permission before the completion notification can be shown
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }

        if (utils.isReadPermissionGranted) {
            onMediaReceive()
        } else {
            Timber.d("Requesting read permissions")
            utils.requestReadPermissions(this)
        }
    }

    override fun finish() {
        mediaCompressor.cancelAllOperations()
        CompressionService.stop(this)
        CacheCleaner.scheduleCleanup(this)
        super.finish()
    }

    override fun onStart() {
        super.onStart()
        isInForeground = true
    }

    override fun onStop() {
        // keep compressing while the app is in the background; the foreground service holds the process
        isInForeground = false
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
            CompressionService.start(this)
            // callback
            mediaCompressor.compressFiles(this, receivedMedia) { outcome ->
                // ffmpeg invokes this callback on its own worker thread, every UI call must be posted
                runOnUiThread {
                    CompressionService.stop(this@HandleMediaActivity)
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
        if (!isInForeground) {
            Notifications.notifyFinished(this, getString(R.string.ffmpeg_error))
        }
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
                if (result.isCompleteFailure) {
                    Toast.makeText(this, getString(R.string.save_failed_toast), Toast.LENGTH_LONG).show()
                }
                showResult(outcome, result)
            }
        }.start()
    }

    /** Result summary: size before/after, ratio and elapsed time. Sharing has been removed. */
    private fun showResult(outcome: MediaCompressor.CompressionOutcome, saveResult: MediaSaver.SaveResult?) {
        val savedLocation = saveResult?.location
        val savedUris = saveResult?.savedUris.orEmpty()
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

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.result_dialog_title)
            .setMessage(lines.joinToString("\n"))
            .setCancelable(false)

        if (savedLocation.isNullOrBlank()) {
            // sharing is gone, so an unsaved result still needs an explicit way out
            builder.setPositiveButton(R.string.result_save) { _, _ -> saveOnly(outcome) }
            builder.setNegativeButton(R.string.close) { _, _ -> finish() }
        } else {
            builder.setPositiveButton(R.string.result_done) { _, _ -> finish() }
            if (savedUris.isNotEmpty()) {
                builder.setNeutralButton(R.string.result_open) { _, _ -> openFile(savedUris.first()) }
            }
        }

        // only when the app is in the background, otherwise the dialog is already visible
        if (!isInForeground) {
            Notifications.notifyFinished(this, lines.take(2).joinToString(" · "))
        }
        builder.show()
    }

    /** Opens a saved output in whatever app can handle it. */
    private fun openFile(uri: Uri) {
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val opened = runCatching { startActivity(intent) }.isSuccess
        if (opened) {
            finish()
        } else {
            Toast.makeText(this, getString(R.string.error_unknown_filetype), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Saves the compressed files and closes, without re-opening the result dialog.
     * Used when the user turned auto-save off and presses "save" in the result dialog.
     */
    private fun saveOnly(outcome: MediaCompressor.CompressionOutcome) {
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
                val message = when {
                    result.isSuccess -> getString(R.string.save_success_toast, result.savedCount, result.location)
                    result.savedCount > 0 -> getString(R.string.save_partial_toast, result.savedCount, result.failedCount)
                    else -> getString(R.string.save_failed_toast)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }
}
