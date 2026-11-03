package com.caydey.ffshare.utils

import android.net.Uri
import com.arthenica.ffmpegkit.MediaInformation
import timber.log.Timber
import java.util.StringJoiner
import kotlin.math.ceil

class FFmpegParamMaker(val settings: Settings, val utils: Utils) {
    fun create(inputFile: Uri, mediaInformation: MediaInformation, mediaType: Utils.MediaType, outputMediaType: Utils.MediaType): String {
        // custom params
        if (utils.isImage(outputMediaType) && settings.customImageParams.isNotEmpty()) return settings.customImageParams
        if (utils.isVideo(outputMediaType) && settings.customVideoParams.isNotEmpty()) return settings.customVideoParams
        if (utils.isAudio(outputMediaType) && settings.customAudioParams.isNotEmpty()) return settings.customAudioParams

        val params = StringJoiner(" ")
        val videoFormatParams = StringJoiner(",")

        // resolve the encoder once, a rejected hardware attempt is retried with the software one
        val requestedCodec = settings.videoCodec
        val videoCodec = if (requestedCodec.isHardware && forceSoftwareCodec) {
            Settings.VideoCodecOpts.H264
        } else {
            requestedCodec
        }
        val hardwareEncoder = videoCodec.isHardware

//        val (inputVideoCodec, inputAudioCodec) = getMediaCodecs(mediaInformation)

        // preset, webp does not support this and hardware encoders have no x264 preset
        if (outputMediaType != Utils.MediaType.WEBP && !hardwareEncoder) {
            params.add("-preset ${settings.compressionPreset}")
        }

        if (utils.isAudio(mediaType) || utils.isVideo(mediaType)) {
            // add audio codec
            if (settings.audioCodec != Settings.AudioCodecOpts.DEFAULT) {
                params.add("-c:a ${settings.audioCodec.raw}")
            }
        }

        var videoScaleApplied = false
        // videos and images max resolution
        if (utils.isVideo(mediaType) || utils.isImage(mediaType)) {
            val (resolutionWidth, resolutionHeight) = utils.getMediaResolution(inputFile, mediaType)
            val isPortrait = resolutionHeight > resolutionWidth
            val resolution = if (isPortrait) resolutionWidth else resolutionHeight
            val maxResolution = if (utils.isImage(mediaType)) settings.maxImageResolution else settings.maxVideoResolution
            // only reduce resolution
            if (resolution > maxResolution && maxResolution != 0) {
                videoScaleApplied = true
                var pixelRounding = "-1"
                // H.26x videos require dimensions to be divisible by 2
                if (utils.isVideo(outputMediaType) && videoCodec in setOf(
                        Settings.VideoCodecOpts.H264,
                        Settings.VideoCodecOpts.H265,
                        Settings.VideoCodecOpts.H264_HW,
                        Settings.VideoCodecOpts.H265_HW
                    )
                ) {
                    pixelRounding = "-2"
                }
                if (isPortrait) {
                    videoFormatParams.add("scale=$maxResolution:$pixelRounding,setsar=1")
                } else {
                    videoFormatParams.add("scale=$pixelRounding:$maxResolution,setsar=1")
                }
            }
        }

        // video
        if (utils.isVideo(outputMediaType)) { // check outputMediaType not mediaType because conversions
            // pixel format: MediaCodec encoders take nv12, ffmpeg's software encoders take yuv420p
            videoFormatParams.add(if (hardwareEncoder) "format=nv12" else "format=yuv420p")

            // video codec
            when {
                hardwareEncoder -> params.add("-c:v ${videoCodec.raw}")
                videoCodec != Settings.VideoCodecOpts.DEFAULT -> params.add("-c:v ${videoCodec.raw}")
                // ffmpeg-kit builds also register h264_mediacodec as "h264", and ffmpeg picks it
                // before libx264 when no encoder is requested. The hardware encoder refuses the
                // yuv420p input we enforce above on many devices (CodecException 0xffffffc3),
                // so pin the software encoder unless the output is webm/vp9.
                outputMediaType != Utils.MediaType.WEBM -> params.add("-c:v ${Settings.VideoCodecOpts.H264.raw}")
            }

            if (hardwareEncoder) {
                // MediaCodec has no CRF: without an explicit bitrate it encodes at its own
                // default and files can grow. -g also overrides the default i-frame-interval of 1.
                params.add("-b:v ${hardwareBitrateKbps(mediaInformation, mediaType, inputFile)}k")
                params.add("-g $HARDWARE_GOP")
            } else {
                // crf
                params.add("-crf ${settings.videoCrf}")
            }

            // H.26x requires dimensions to be divisible by 2, video scaling will account for this if applied
            if (!videoScaleApplied) {
                // stream 0 is not guaranteed to be the video stream, and an unknown size must not
                // crash the whole compression
                val stream = mediaInformation.getStreams().firstOrNull { it.getType()?.lowercase() == "video" }
                val width = stream?.getWidth()
                val height = stream?.getHeight()
                if (width != null && height != null && (width.rem(2) != 0L || height.rem(2) != 0L)) {
                    videoFormatParams.add("crop=trunc(iw/2)*2:trunc(ih/2)*2")
                    // could also use "pad=ceil(iw/2)*2:ceil(ih/2)*2" to add column/row of black pixels
                }
            }

            //  max file size (limit the bitrate to achieve this)
            //  hardware encoders already got a -b:v above and do not accept -maxrate reliably
            if (settings.videoMaxFileSize != 0 && !hardwareEncoder) {
                // ffmpeg does not like -maxrate & -bufsize params when the output file is webm
                if (outputMediaType != Utils.MediaType.WEBM) {
                    // Calculate maximum bitrate in kbps.
                    // Ceil duration to ensure the maximum is strict. toInt to floor result, ffmpeg takes ints.
                    val maxBitrate = (settings.videoMaxFileSize * 8 / ceil(mediaInformation.getDuration()?.toFloat() ?: 0f)).toInt()
                    Timber.d("Maximum bitrate for targeted filesize (%dK): %dk", settings.videoMaxFileSize, maxBitrate)

                    // audio can have at most one third of the total bitrate
                    val audioSplit = maxBitrate / 3
                    // round audio bitrate down to 192,128,96,64,32,24
                    val audioBitrate = if (audioSplit > 192) 192 // maximum audio bitrate is 192k
                    else if (audioSplit > 128) 128
                    else if (audioSplit > 96) 96
                    else if (audioSplit > 64) 64
                    else if (audioSplit > 32) 32
                    else 24 // minimum audio bitrate is 24k

                    // set audio bitrate
                    params.add("-b:a ${audioBitrate}k")

                    // set max video bitrate
                    val videoBitrate = (maxBitrate - audioBitrate)
                    params.add("-maxrate ${videoBitrate}k -bufsize ${videoBitrate}k")
                }
            }
        }

        if (videoFormatParams.length() > 0) {
            params.add("-vf \"$videoFormatParams\"")
        }

        // jpeg quality
        if (outputMediaType == Utils.MediaType.JPEG || outputMediaType == Utils.MediaType.JPG) {
            params.add("-qscale:v ${settings.jpegQscale}")
        }

        return params.toString()
    }

    /**
     * MediaCodec has no constant quality mode, so hardware encoding needs an explicit bitrate.
     * Prefers the "video max file size" setting when it is set, otherwise picks a bitrate that
     * matches the output height.
     */
    private fun hardwareBitrateKbps(mediaInformation: MediaInformation, mediaType: Utils.MediaType, inputFile: Uri): Int {
        val duration = mediaInformation.getDuration()?.toFloat() ?: 0f
        val maxFileSize = settings.videoMaxFileSize // KiB
        if (maxFileSize != 0 && duration > 0f) {
            val totalKbps = (maxFileSize * 8 / ceil(duration)).toInt()
            return (totalKbps - HW_AUDIO_KBPS).coerceAtLeast(MIN_HW_VIDEO_KBPS)
        }
        val (_, sourceHeight) = utils.getMediaResolution(inputFile, mediaType)
        val maxResolution = settings.maxVideoResolution
        val height = if (maxResolution == 0) sourceHeight else minOf(sourceHeight, maxResolution)
        return when {
            height >= 2160 -> 20000
            height >= 1440 -> 12000
            height >= 1080 -> 6000
            height >= 720 -> 3000
            height >= 480 -> 1500
            else -> 800
        }
    }

    companion object {
        // MediaCodec defaults to an i-frame interval of 1 frame, which bloats the output
        private const val HARDWARE_GOP = 48
        private const val HW_AUDIO_KBPS = 128
        private const val MIN_HW_VIDEO_KBPS = 400

        /**
         * Set to true when a hardware encode failed on this device, the next attempt then falls
         * back to the software encoder.
         */
        @Volatile
        var forceSoftwareCodec = false
    }

    private fun getMediaCodecs(mediaInformation: MediaInformation): Pair<Settings.VideoCodecOpts?, Settings.AudioCodecOpts?> {
        var audioCodec: Settings.AudioCodecOpts? = null
        var videoCodec: Settings.VideoCodecOpts? = null
        for (stream in mediaInformation.getStreams()) {
            if (stream.getType()?.lowercase() == "video") {
                videoCodec = Settings.VideoCodecOpts.parseCodec(stream.getCodec());
            } else if (stream.getType()?.lowercase() == "audio") {
                audioCodec = Settings.AudioCodecOpts.parseCodec((stream.getCodec()))
            }
        }
        return Pair(videoCodec, audioCodec)
    }
}
