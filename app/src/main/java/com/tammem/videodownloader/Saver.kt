package com.tammem.videodownloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Copies a finished file into the phone's public Download folder. */
object Saver {
    const val FOLDER = "محمّل الفيديو"
    val displayPath: String get() = "Download/$FOLDER"

    data class Saved(val uri: Uri, val name: String, val mime: String)

    fun save(context: Context, src: File): Saved {
        val name = cleanName(src.name)
        val mime = mimeFor(src.extension)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(context, src, name, mime)
        } else {
            saveLegacy(context, src, name, mime)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(context: Context, src: File, name: String, mime: String): Saved {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Cannot open output")
            out.use { o -> src.inputStream().use { it.copyTo(o, 1 shl 16) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // Android renames duplicates ("name (1).mp4"); show the real name.
        val finalName = try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        } ?: name
        return Saved(uri, finalName, mime)
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, src: File, name: String, mime: String): Saved {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var target = File(dir, name)
        var i = 1
        while (target.exists()) {
            target = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        src.copyTo(target)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", target)
        return Saved(uri, target.name, mime)
    }

    private fun cleanName(raw: String): String {
        val ext = raw.substringAfterLast('.', "")
        var base = raw.substringBeforeLast('.')
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
            .trim()
            .trim('.')
        if (base.isEmpty()) base = "video"
        if (base.length > 80) base = base.take(80).trim()
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        else -> "application/octet-stream"
    }
}
