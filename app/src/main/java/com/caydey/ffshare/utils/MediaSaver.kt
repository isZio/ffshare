package com.caydey.ffshare.utils

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import timber.log.Timber
import java.io.File

/**
 * Saves the compressed media files out of the (temporary) cache into a persistent location.
 *
 * Two destinations are supported:
 *  - a user picked folder (SAF, [android.content.Intent.ACTION_OPEN_DOCUMENT_TREE]), which needs
 *    no storage permission at all and works from API 26;
 *  - the default system gallery (MediaStore), which puts files into
 *    Movies/FFShare, Pictures/FFShare or Music/FFShare depending on the media type.
 */
object MediaSaver {
    const val ALBUM_DIR = "zShrink"

    data class SaveResult(
        val savedCount: Int,
        val failedCount: Int,
        val location: String, // human readable destination, e.g. "Movies/zShrink"
        val needsStoragePermission: Boolean = false,
        val savedUris: List<Uri> = emptyList() // uris of the copies that can be opened again
    ) {
        val isSuccess: Boolean get() = savedCount > 0 && failedCount == 0
        val isCompleteFailure: Boolean get() = savedCount == 0
    }

    /**
     * Copies every uri to a persistent destination.
     *
     * @param treeUri persisted document tree uri picked by the user, or null/blank for the gallery
     */
    fun saveAll(context: Context, uris: List<Uri>, treeUri: String?): SaveResult {
        Timber.d("Saving %d file(s), target tree: %s", uris.size, treeUri)
        if (uris.isEmpty()) return SaveResult(0, 0, "")

        val documentTree = treeUri
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it)) }.getOrNull() }
            ?.takeIf { it.exists() && it.canWrite() }

        if (documentTree != null) {
            return saveToDocumentTree(context, documentTree, uris)
        }
        return saveToGallery(context, uris)
    }

    private fun saveToDocumentTree(context: Context, tree: DocumentFile, uris: List<Uri>): SaveResult {
        var saved = 0
        var failed = 0
        val savedUris = mutableListOf<Uri>()
        uris.forEach { uri ->
            val mime = mimeTypeOf(context, uri)
            val name = uniqueName(tree, fileNameOf(context, uri, mime))
            val document = runCatching { tree.createFile(mime, name) }.getOrNull()
            val output = document?.uri?.let { runCatching { context.contentResolver.openOutputStream(it) }.getOrNull() }
            if (document != null && output != null) {
                val copied = runCatching {
                    output.use { out ->
                        context.contentResolver.openInputStream(uri)?.use { it.copyTo(out) }
                            ?: throw IllegalStateException("unable to open $uri")
                    }
                }.isSuccess
                if (copied) {
                    saved++
                    savedUris.add(document.uri)
                } else {
                    failed++
                    runCatching { document.delete() }
                    Timber.d("Failed to copy %s into tree", uri)
                }
            } else {
                failed++
                runCatching { document?.delete() }
                Timber.d("Unable to create file '%s' in tree", name)
            }
        }
        val location = tree.name ?: tree.uri.lastPathSegment ?: ""
        return SaveResult(saved, failed, location, savedUris = savedUris)
    }

    private fun saveToGallery(context: Context, uris: List<Uri>): SaveResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            !Utils(context).isWritePermissionGranted
        ) {
            Timber.d("Write permission missing, cannot write to the gallery on API < 29")
            return SaveResult(0, uris.size, "", needsStoragePermission = true)
        }

        var saved = 0
        var failed = 0
        var lastLocation = ""
        val savedUris = mutableListOf<Uri>()
        uris.forEach { uri ->
            val mime = mimeTypeOf(context, uri)
            val name = fileNameOf(context, uri, mime)
            val (location, savedUri) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(context, uri, name, mime)
            } else {
                saveToPublicDir(context, uri, name, mime) to null
            }
            if (location != null) {
                saved++
                lastLocation = location
                savedUri?.let { savedUris.add(it) }
            } else {
                failed++
            }
        }
        return SaveResult(saved, failed, lastLocation, savedUris = savedUris)
    }

    /** API 29+: MediaStore insert with RELATIVE_PATH, no permission required. */
    private fun saveToMediaStore(context: Context, uri: Uri, name: String, mime: String): Pair<String?, Uri?> {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${relativeDirFor(mime)}/$ALBUM_DIR")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = when (kindOf(mime)) {
            Kind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Kind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Kind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val item = runCatching { context.contentResolver.insert(collection, values) }.getOrNull()
        if (item == null) {
            Timber.d("MediaStore insert returned null for '%s'", name)
            return null to null
        }
        val copied = runCatching {
            context.contentResolver.openOutputStream(item)?.use { out ->
                context.contentResolver.openInputStream(uri)?.use { it.copyTo(out) }
                    ?: throw IllegalStateException("unable to open $uri")
            } ?: throw IllegalStateException("unable to open output stream")
        }.isSuccess
        if (!copied) {
            runCatching { context.contentResolver.delete(item, null, null) }
            return null to null
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        runCatching { context.contentResolver.update(item, values, null, null) }
        return "${relativeDirFor(mime)}/$ALBUM_DIR" to item
    }

    /** API 26-28: plain file in the public media directory + media scanner, needs WRITE permission. */
    private fun saveToPublicDir(context: Context, uri: Uri, name: String, mime: String): String? {
        val dir = File(Environment.getExternalStoragePublicDirectory(relativeDirFor(mime)), ALBUM_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            Timber.d("Unable to create %s", dir.absolutePath)
            return null
        }
        val target = File(dir, uniqueFileName(dir, name))
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("unable to open $uri")
        }.isSuccess
        if (!copied) {
            runCatching { target.delete() }
            return null
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return "${relativeDirFor(mime)}/$ALBUM_DIR"
    }

    private enum class Kind { VIDEO, IMAGE, AUDIO }

    private fun kindOf(mime: String?): Kind = when {
        mime?.startsWith("video/") == true -> Kind.VIDEO
        mime?.startsWith("audio/") == true -> Kind.AUDIO
        else -> Kind.IMAGE
    }

    private fun relativeDirFor(mime: String?): String = when (kindOf(mime)) {
        Kind.VIDEO -> Environment.DIRECTORY_MOVIES
        Kind.AUDIO -> Environment.DIRECTORY_MUSIC
        Kind.IMAGE -> Environment.DIRECTORY_PICTURES
    }

    private fun mimeTypeOf(context: Context, uri: Uri): String {
        val fromResolver = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (!fromResolver.isNullOrBlank()) return fromResolver
        return when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "opus" -> "audio/opus"
            else -> "application/octet-stream"
        }
    }

    private fun fileNameOf(context: Context, uri: Uri, mime: String): String {
        val queried = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        if (!queried.isNullOrBlank()) return queried
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        return fallback ?: "zShrink_${System.currentTimeMillis()}.${defaultExtensionFor(mime)}"
    }

    private fun defaultExtensionFor(mime: String): String = when {
        mime.contains("mp4") -> "mp4"
        mime.contains("matroska") -> "mkv"
        mime.contains("webm") -> "webm"
        mime.contains("jpeg") -> "jpg"
        mime.contains("png") -> "png"
        mime.contains("gif") -> "gif"
        mime.contains("webp") -> "webp"
        mime.contains("mpeg") -> "mp3"
        mime.contains("ogg") -> "ogg"
        mime.contains("wav") -> "wav"
        mime.contains("aac") -> "aac"
        mime.contains("opus") -> "opus"
        else -> "bin"
    }

    private fun uniqueName(dir: DocumentFile, name: String): String {
        if (dir.findFile(name) == null) return name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (true) {
            val candidate = if (ext.isBlank()) "$base ($i)" else "$base ($i).$ext"
            if (dir.findFile(candidate) == null) return candidate
            i++
        }
    }

    private fun uniqueFileName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (true) {
            val candidate = if (ext.isBlank()) "$base ($i)" else "$base ($i).$ext"
            if (!File(dir, candidate).exists()) return candidate
            i++
        }
    }
}
