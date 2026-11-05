package com.caydey.ffshare.utils


import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.caydey.ffshare.R
import com.caydey.ffshare.utils.logs.Log
import com.caydey.ffshare.utils.logs.LogsDbHelper
import timber.log.Timber
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong


class MediaCompressor(private val context: Context) {

    /** Everything the caller needs to report a finished compression. */
    data class CompressionOutcome(
        val uris: ArrayList<Uri>,
        val errorOutput: String?,
        val inputBytes: Long,
        val outputBytes: Long,
        val elapsedMillis: Long
    )

    /**
     * Progress of the file currently being compressed. [remainingMillis] is null for images,
     * where ffmpeg cannot report statistics (and [isImage] is true).
     */
    data class Progress(
        val currentFile: Int,
        val totalFiles: Int,
        val percent: Float,
        val remainingMillis: Int?,
        val isImage: Boolean
    )

    private val utils: Utils by lazy { Utils(context) }
    private val settings: Settings by lazy { Settings(context) }
    private val logsDbHelper by lazy { LogsDbHelper(context) }

    private val ffmpegParamMaker = FFmpegParamMaker(settings, utils)

    fun cancelAllOperations() {
        Timber.d("Canceling all ffmpeg operations")
        FFmpegKit.cancel()
    }

    @SuppressLint("SetTextI18n")
    fun compressSingleFile(
        activity: Activity,
        inputFileUri: Uri,
        currentFile: Int = 1,
        totalFiles: Int = 1,
        onProgress: (Progress) -> Unit = {},
        successHandler: (uri: Uri, inputFileSize: Long, outputFileSize: Long) -> Unit,
        failureHandler: (errorOutput: String?) -> Unit
    ) {
        // a cancel can reach us from two places now (the in-app button and the notification
        // action); whichever lands first wins, so the caller is never called back twice
        val settled = AtomicBoolean(false)
        fun failOnce(errorOutput: String?) {
            if (settled.compareAndSet(false, true)) failureHandler(errorOutput)
        }
        fun successOnce(uri: Uri, inputFileSize: Long, outputFileSize: Long) {
            if (settled.compareAndSet(false, true)) successHandler(uri, inputFileSize, outputFileSize)
        }

        val txtFfmpegCommand: TextView = activity.findViewById(R.id.txtFfmpegCommand)
        val txtInputFile: TextView = activity.findViewById(R.id.txtInputFile)
        val txtInputFileSize: TextView = activity.findViewById(R.id.txtInputFileSize)
        val txtOutputFile: TextView = activity.findViewById(R.id.txtOutputFile)
        val txtOutputFileSize: TextView = activity.findViewById(R.id.txtOutputFileSize)
        val txtProcessedTime: TextView = activity.findViewById(R.id.txtProcessedTime)
        val txtProcessedTimeTotal: TextView = activity.findViewById(R.id.txtProcessedTimeTotal)
        val txtProcessedPercent: TextView = activity.findViewById(R.id.txtProcessedPercent)
        val processedTableRow: TableRow = activity.findViewById(R.id.processedTableRow)
        val progressBar: ProgressBar = activity.findViewById(R.id.progressBar)
        val txtRemaining: TextView = activity.findViewById(R.id.txtRemaining)

        // cancel button
        val btnCancel: Button = activity.findViewById(R.id.btnCancel)
        btnCancel.setOnClickListener {
            Toast.makeText(context, context.getString(R.string.ffmpeg_canceled), Toast.LENGTH_LONG).show()
            // cancel all ffmpeg operations
            cancelAllOperations()

            failOnce(null) // a cancel is a fail
        }

        val mediaType = utils.getMediaType(inputFileUri)
        if (!utils.isSupportedMediaType(mediaType)) { // not supported show error
            Toast.makeText(context, context.getString(R.string.error_unknown_filetype), Toast.LENGTH_LONG).show()
            failOnce(null)
            return
        }

        // don't show progress when compressing images (not possible)
        val showProgress = !utils.isImage(mediaType)
        if (!showProgress) {
            processedTableRow.visibility = View.INVISIBLE
            progressBar.visibility = View.INVISIBLE
            txtRemaining.visibility = View.INVISIBLE
        }

        val inputFileName = utils.getFilenameFromUri(inputFileUri) ?: "unknown"

        // get output file, (random uuid, custom name, original name)
        val (outputFile, outputMediaType) = utils.getCacheOutputFile(inputFileUri, mediaType)

        // get Uri from File, needs to be this way not Uri.fromFile(...) to go through security
        val outputFileUri = FileProvider.getUriForFile(context, context.applicationContext.packageName+".fileprovider", outputFile)

        // need to create new saf param as they are one-use
        val mediaInformation = FFprobeKit.getMediaInformation(FFmpegKitConfig.getSafParameterForRead(context, inputFileUri)).getMediaInformation()

        if (mediaInformation == null) {
            Timber.d("Unable to get media information, throwing error")
            Toast.makeText(context, context.getString(R.string.error_invalid_file), Toast.LENGTH_LONG).show()
            failOnce(null)
            return
        }

        val inputFileSize = mediaInformation.getSize()?.toLong() ?: 0 // get input file size

        var duration = 0 // default duration for image
        if (showProgress) {
            // invalid video file if ffprobe cant parse duration and size
            if (mediaInformation.getDuration() == null || mediaInformation.getSize() == null) {
                Timber.d("Unable to get size & duration for media, throwing error")
                Toast.makeText(context, context.getString(R.string.error_invalid_file), Toast.LENGTH_LONG).show()
                failOnce(null)
                return
            }
            duration = ((mediaInformation.getDuration()?.toFloat() ?: 0f) * 1_000).toInt()
        }

        val params = ffmpegParamMaker.create(inputFileUri, mediaInformation, mediaType, outputMediaType)
        val inputSaf: String = FFmpegKitConfig.getSafParameterForRead(context, inputFileUri)
        val outputSaf: String = FFmpegKitConfig.getSafParameterForWrite(context, outputFileUri)
        val command = "-y -i $inputSaf $params $outputSaf"
        val prettyCommand = "ffmpeg -y -i $inputFileName $params ${outputFile.name}"

        // set TextViews
        Handler(Looper.getMainLooper()).post {
            txtFfmpegCommand.text = prettyCommand
            txtInputFile.text = inputFileName
            txtInputFileSize.text = utils.bytesToHuman(inputFileSize)
            txtOutputFile.text = outputFile.name
            txtOutputFileSize.text = utils.bytesToHuman(0)
            txtProcessedTime.text = utils.millisToMicrowaveTime(0)
            txtProcessedTimeTotal.text = utils.millisToMicrowaveTime(duration)
            txtProcessedPercent.text = context.getString(R.string.format_percentage, 0.0f)
            progressBar.progress = 0
            txtRemaining.text = context.getString(R.string.remaining) + " " + utils.millisToMicrowaveTime(duration)
            // seed the notification before ffmpeg emits its first statistics event
            onProgress(
                Progress(
                    currentFile = currentFile,
                    totalFiles = totalFiles,
                    percent = 0f,
                    remainingMillis = if (showProgress) duration else null,
                    isImage = !showProgress
                )
            )
        }

        Timber.d("Executing ffmpeg command: 'ffmpeg %s'", command)
        val finished = AtomicBoolean(false)
        val lastProgressAt = AtomicLong(SystemClock.elapsedRealtime())
        val watchdog = Handler(Looper.getMainLooper())
        val ffmpegSession = FFmpegKit.executeAsync(command, { session ->
            finished.set(true)
            watchdog.removeCallbacksAndMessages(null)
            // completed
            if (session.getReturnCode()?.isValueSuccess() == false) { // failed
                if (session.getReturnCode()?.isValueCancel() == true) {
                    // cancelled from the notification or the stall watchdog: unwind the chain so
                    // the activity can close instead of waiting for a callback that never comes
                    Timber.d("ffmpeg session cancelled")
                    failOnce(null)
                } else { // failure was not caused by a cancel
                    Timber.d("ffmpeg command failed")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, context.getString(R.string.ffmpeg_error), Toast.LENGTH_LONG).show()
                    }
                    // save log

                    logsDbHelper.addLog(Log(
                        prettyCommand,
                        inputFileName,
                        outputFile.name,
                        false,
                        session.getOutput(),
                        inputFileSize,
                        -1
                    ))
                    Timber.e("ffmpeg failed: %s", session.getOutput())
                    failOnce(session.getOutput())
                }
            } else { // success
                Timber.d("ffmpeg command executed successfully")
                if (settings.copyExifTags && ExifTools.isValidType(mediaType)) {
                    Timber.d("copying exif tags")
                    // a broken exif block must not turn a successful compression into a crash
                    runCatching {
                        context.contentResolver.openInputStream(inputFileUri)?.use { input ->
                            ExifTools.copyExif(input, outputFile)
                        }
                    }.onFailure { Timber.w(it, "unable to copy exif tags") }
                }
                val outputFileCurrentSize = outputFile.length()
                // """Only the original thread that created a view hierarchy can touch its views."""
                Handler(Looper.getMainLooper()).post {
                    // update TextViews to their final values 97.8% -> 100.0%
                    txtProcessedPercent.text = context.getString(R.string.format_percentage, 100.0f)
                    txtProcessedTime.text = utils.millisToMicrowaveTime(duration)
                    progressBar.progress = 100
                    txtRemaining.text = context.getString(R.string.remaining) + " " + utils.millisToMicrowaveTime(0)
                    if (outputFileCurrentSize > 0) {
                        txtOutputFileSize.text = utils.bytesToHuman(outputFileCurrentSize)
                    }
                }

                logsDbHelper.addLog(Log(
                    prettyCommand,
                    inputFileName,
                    outputFile.name,
                    true,
                    session.getOutput(),
                    inputFileSize,
                    outputFileCurrentSize
                ))
                // callback
                successOnce(outputFileUri, inputFileSize, outputFileCurrentSize)
            }
        }, { /* logs */ }, { statistics ->
            lastProgressAt.set(SystemClock.elapsedRealtime())
            // update TextViews with stats
            Handler(Looper.getMainLooper()).post {
                var percent = 0f
                var remainingMillis: Int? = null
                if (showProgress) { // only show time processed if video
                    percent = if (duration > 0) {
                        (statistics.time.toFloat() / duration * 100f).coerceIn(0f, 100f)
                    } else {
                        0f
                    }
                    progressBar.progress = percent.toInt()
                    txtProcessedPercent.text = context.getString(R.string.format_percentage, percent)
                    txtProcessedTime.text = utils.millisToMicrowaveTime(statistics.time.toInt())
                    val remaining = (duration - statistics.time).coerceAtLeast(0.0).toInt()
                    remainingMillis = remaining
                    txtRemaining.text = context.getString(R.string.remaining) + " " + utils.millisToMicrowaveTime(remaining)
                }
                txtOutputFileSize.text = utils.bytesToHuman(statistics.size)
                onProgress(
                    Progress(
                        currentFile = currentFile,
                        totalFiles = totalFiles,
                        percent = percent,
                        remainingMillis = remainingMillis,
                        isImage = !showProgress
                    )
                )
            }
        })

        // Stall watchdog: only a session that stops making progress is cancelled. A long but
        // healthy compression (big file, slow device) keeps emitting statistics and is never
        // killed by a fixed deadline.
        if (!finished.get()) {
            val watchdogTask = object : Runnable {
                override fun run() {
                    if (finished.get()) return
                    val idleMs = SystemClock.elapsedRealtime() - lastProgressAt.get()
                    if (idleMs >= STALL_TIMEOUT_MS) {
                        if (finished.compareAndSet(false, true)) {
                            Timber.w("ffmpeg made no progress for %d ms, cancelling session %d", idleMs, ffmpegSession.sessionId)
                            FFmpegKit.cancel(ffmpegSession.sessionId)
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(context, context.getString(R.string.error_timeout), Toast.LENGTH_LONG).show()
                            }
                            failOnce(context.getString(R.string.error_timeout))
                        }
                    } else {
                        watchdog.postDelayed(this, WATCHDOG_INTERVAL_MS)
                    }
                }
            }
            watchdog.postDelayed(watchdogTask, STALL_TIMEOUT_MS)
        }
    }

    private companion object {
        // no progress at all for this long means the session is stuck
        const val STALL_TIMEOUT_MS = 5 * 60 * 1000L
        const val WATCHDOG_INTERVAL_MS = 30 * 1000L
    }

    fun compressFiles(
        activity: Activity,
        inputFilesUri: ArrayList<Uri>,
        onProgress: (Progress) -> Unit = {},
        callback: (outcome: CompressionOutcome) -> Unit
    ) {
        val txtCommandNumber: TextView = activity.findViewById(R.id.txtCommandNumber)

        val inputFilesCount = inputFilesUri.size

        val compressedFiles = ArrayList<Uri>()

        var totalInputFileSize = 0L
        var totalOutputFileSize = 0L

        val startTime = SystemClock.elapsedRealtime()

        // last ffmpeg error output, null when every file succeeded
        var lastErrorOutput: String? = null

        // since we are working with callbacks a simple for loop wont work
        lateinit var iteratorFunction: (Int, Boolean) -> Unit
        iteratorFunction = fun(i, error) {
            // a failed (or cancelled) file aborts the batch: starting the next one would leave
            // ffmpeg working while the caller is already finishing up
            if (!error && i < inputFilesCount) {
                Timber.d("Processing %d of %d files", i+1, inputFilesCount)

                if (inputFilesCount > 1) { // show "1 of N" label if N > 1
                    Handler(Looper.getMainLooper()).post {
                        txtCommandNumber.text = context.getString(R.string.command_x_of_y, i+1, inputFilesCount)
                    }
                }

                compressSingleFile(
                    activity = activity,
                    inputFileUri = inputFilesUri[i],
                    currentFile = i + 1,
                    totalFiles = inputFilesCount,
                    onProgress = onProgress,
                    failureHandler = { errorOutput ->
                        // keep the ffmpeg output so the caller can show what actually went wrong
                        if (errorOutput != null) lastErrorOutput = errorOutput
                        // if 1 file fails don't add it to compressedFiles array
                        iteratorFunction(i+1, true) // call to self with error flag true
                    },
                    successHandler = { uri, inputFileSize, outputFileSize ->
                        totalInputFileSize += inputFileSize
                        totalOutputFileSize += outputFileSize
                        compressedFiles.add(uri)
                        iteratorFunction(i+1, false) // call to self with error flag false
                    }
                )
            }
            if (i >= inputFilesCount || error) { // end of iterations
                val elapsedMillis = SystemClock.elapsedRealtime() - startTime
                Timber.d(
                    "Compression finished in %d ms, %d -> %d bytes",
                    elapsedMillis, totalInputFileSize, totalOutputFileSize
                )
                callback(
                    CompressionOutcome(
                        uris = compressedFiles,
                        errorOutput = lastErrorOutput,
                        inputBytes = totalInputFileSize,
                        outputBytes = totalOutputFileSize,
                        elapsedMillis = elapsedMillis
                    )
                )
            }
        }
        iteratorFunction(0, false) // start iterations
    }

}
