package com.muclip.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.muclip.core.StreamingFileTransfer
import android.media.MediaScannerConnection
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

object AndroidStorageUtils {

    /**
     * Resolves the display name and exact byte size from any SAF `content://` Uri
     * without copying the file to disk.
     */
    fun getFileMetaFromUri(context: Context, uri: Uri): Pair<String, Long> {
        var fileName = "file_${System.currentTimeMillis()}"
        var fileSize: Long = -1L

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) fileName = name
                    }
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (fileSize < 0L) {
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length >= 0L) {
                        fileSize = afd.length
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return Pair(fileName, if (fileSize >= 0L) fileSize else 0L)
    }

    /**
     * Opens a direct streaming [InputStream] from a SAF `content://` Uri
     * without caching or duplicating the file on disk.
     */
    fun openSafInputStream(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Creates a MediaStore pending entry for [displayName] and returns a direct
     * [OutputStream] that writes straight into the public Download/MuClip directory.
     *
     * This lets an incoming transfer be decrypted and written **directly** to its final
     * public location, eliminating the staging file + post-transfer copy entirely.
     * Call [finalizePendingDownload] after the transfer completes (successfully or not)
     * to clear IS_PENDING or discard the partial entry.
     *
     * Returns null on failure; callers must fall back to a private-directory transfer.
     */
    fun createPendingDownloadStream(context: Context, displayName: String): Pair<Uri, OutputStream>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val mimeType = getMimeType(displayName)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MuClip")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    val output = resolver.openOutputStream(uri)
                    if (output != null) {
                        return uri to output
                    }
                    // Failed to open stream — discard the orphaned pending entry
                    try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Legacy fallback (< Android 10 or if MediaStore insert fails): write directly to public Download/MuClip
        return try {
            val publicDownload = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "MuClip"
            )
            if (!publicDownload.exists()) {
                publicDownload.mkdirs()
            }
            var targetFile = File(publicDownload, displayName)
            if (targetFile.exists()) {
                val stem = targetFile.nameWithoutExtension.ifEmpty { "file" }
                val ext = targetFile.extension.let { if (it.isNotEmpty()) ".$it" else "" }
                var counter = 1
                while (targetFile.exists()) {
                    targetFile = File(publicDownload, "${stem}_$counter$ext")
                    counter++
                }
            }
            Uri.fromFile(targetFile) to FileOutputStream(targetFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Clears IS_PENDING on a [pendingUri] so the file becomes visible in the Downloads UI.
     * Call after a successful transfer.
     */
    fun finalizePendingDownload(context: Context, pendingUri: Uri) {
        try {
            if (pendingUri.scheme == "file") {
                pendingUri.path?.let { path ->
                    MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
                }
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                context.contentResolver.update(pendingUri, values, null, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Discards a pending entry created by [createPendingDownloadStream].
     * Call when the transfer fails, so no truncated file is left behind.
     */
    fun abortPendingDownload(context: Context, pendingUri: Uri) {
        try {
            if (pendingUri.scheme == "file") {
                pendingUri.path?.let { File(it).delete() }
            } else {
                context.contentResolver.delete(pendingUri, null, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun streamWithFixedBuffer(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(StreamingFileTransfer.CHUNK_SIZE)
        while (true) {
            val length = input.read(buffer, 0, StreamingFileTransfer.CHUNK_SIZE)
            if (length < 0) {
                break
            }
            output.write(buffer, 0, length)
        }
        output.flush()
    }

    fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            // 1. 使用安卓系统内置 MimeTypeMap 自动提取
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (!mime.isNullOrBlank()) return mime

            // 2. 仅对系统偶尔缺省的特殊类型做轻量兜底
            return when (ext) {
                "apk" -> "application/vnd.android.package-archive"
                "md", "markdown" -> "text/markdown"
                "7z" -> "application/x-7z-compressed"
                else -> "*/*"
            }
        }
        return "*/*"
    }
}
