package com.tammem.videodownloader

import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Downloads a link "as is": documents, pictures, archives… and files shared from
 * Google Drive, Google Docs and Dropbox.
 */
object DirectFile {

    enum class Kind { PLAIN, DRIVE, DOCS }

    data class Link(val url: String, val kind: Kind, val isFolder: Boolean = false)

    sealed interface Result {
        data class Ok(val file: File, val mime: String?) : Result

        /** The address is a web page, not a file. */
        data object Page : Result

        data class HttpError(val code: Int) : Result
    }

    private val FILE_EXTENSIONS = setOf(
        "pdf", "doc", "docx", "dot", "dotx", "ppt", "pptx", "pps", "ppsx", "xls", "xlsx", "csv",
        "odt", "ods", "odp", "rtf", "txt", "epub", "zip", "rar", "7z", "apk",
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic",
    )

    private const val ID = """([A-Za-z0-9_-]{15,})"""
    private val DRIVE_FILE = Regex("""drive\.google\.com/(?:file/d/|open\?(?:.*&)?id=|uc\?(?:.*&)?id=)$ID""", RegexOption.IGNORE_CASE)
    private val DRIVE_FOLDER = Regex("""drive\.google\.com/(?:drive/(?:u/\d+/)?folders/|embeddedfolderview\?(?:.*&)?id=)""", RegexOption.IGNORE_CASE)
    private val GOOGLE_DOC = Regex("""docs\.google\.com/(document|presentation|spreadsheets)/d/$ID""", RegexOption.IGNORE_CASE)
    private val DROPBOX = Regex("""^https?://(?:www\.)?dropbox\.com/""", RegexOption.IGNORE_CASE)

    /** Google Drive / Docs / Dropbox share links → their direct-download address. */
    fun cloudLink(url: String): Link? {
        if (DRIVE_FOLDER.containsMatchIn(url)) return Link(url, Kind.DRIVE, isFolder = true)
        DRIVE_FILE.find(url)?.let {
            val id = it.groupValues[1]
            return Link("https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t", Kind.DRIVE)
        }
        GOOGLE_DOC.find(url)?.let {
            val (type, id) = it.destructured
            val export = when (type.lowercase()) {
                "document" -> "https://docs.google.com/document/d/$id/export?format=docx"
                "presentation" -> "https://docs.google.com/presentation/d/$id/export/pptx"
                else -> "https://docs.google.com/spreadsheets/d/$id/export?format=xlsx"
            }
            return Link(export, Kind.DOCS)
        }
        if (DROPBOX.containsMatchIn(url)) {
            val direct = when {
                Regex("""[?&]dl=0""").containsMatchIn(url) -> url.replace(Regex("""([?&])dl=0"""), "$1dl=1")
                Regex("""[?&]dl=1""").containsMatchIn(url) -> url
                url.contains('?') -> "$url&dl=1"
                else -> "$url?dl=1"
            }
            return Link(direct, Kind.PLAIN)
        }
        return null
    }

    /** True when the address itself ends in a document / picture / archive name. */
    fun looksLikeFile(url: String): Boolean {
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return ext in FILE_EXTENSIONS
    }

    /**
     * Downloads [url] into [dir]. [onProgress] gets the percentage (or -1 when the size is
     * unknown) and the seconds left, and returns false to stop.
     */
    fun fetch(url: String, dir: File, onProgress: (Float, Long) -> Boolean): Result {
        var current = url
        var conn: HttpURLConnection? = null
        try {
            // Followed by hand: HttpURLConnection does not follow http → https redirects.
            for (hop in 0..8) {
                val c = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", Links.USER_AGENT)
                    setRequestProperty("Accept", "*/*")
                }
                val code = c.responseCode
                if (code in 300..399) {
                    val location = c.getHeaderField("Location")
                    c.disconnect()
                    if (location == null || hop == 8) return Result.HttpError(code)
                    current = URL(URL(current), location).toString()
                    continue
                }
                conn = c
                break
            }
            val c = conn ?: return Result.HttpError(0)
            val code = c.responseCode
            if (code !in 200..299) return Result.HttpError(code)

            val type = c.contentType.orEmpty().substringBefore(';').trim().lowercase()
            if (type == "text/html" || type == "application/xhtml+xml") return Result.Page

            val name = fileName(c.getHeaderField("Content-Disposition"), current, type)
            val file = File(dir, name)
            val total = c.contentLengthLong
            val start = System.currentTimeMillis()
            var done = 0L
            var lastPercent = -2f
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val percent = if (total > 0) done * 100f / total else -1f
                        if (percent < 0f || percent - lastPercent >= 1f) {
                            lastPercent = percent
                            val elapsed = (System.currentTimeMillis() - start) / 1000.0
                            val eta = if (total > 0 && elapsed > 1 && done > 0) {
                                ((total - done) / (done / elapsed)).toLong()
                            } else {
                                -1L
                            }
                            if (!onProgress(percent, eta)) throw Stopped()
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("Connection closed after $done of $total bytes")
            return Result.Ok(file, type.ifEmpty { null })
        } finally {
            conn?.disconnect()
        }
    }

    /** Thrown when the user cancels; the caller ignores it. */
    class Stopped : Exception()

    // ───────────────────────── File names ─────────────────────────

    private val FILENAME_STAR = Regex("""filename\*\s*=\s*(?:[\w-]+)?'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
    private val FILENAME = Regex("""filename\s*=\s*(?:"([^"]+)"|([^;]+))""", RegexOption.IGNORE_CASE)

    /** Content-Disposition name, else the last part of the address, with a sensible extension. */
    fun fileName(disposition: String?, url: String, mime: String): String {
        val fromHeader = disposition?.let { d ->
            FILENAME_STAR.find(d)?.groupValues?.get(1)?.let { decode(it.trim()) }
                ?: FILENAME.find(d)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] }.trim() }
        }
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val fromUrl = decode(path.trimEnd('/').substringAfterLast('/')).takeIf { it.isNotBlank() }
        var name = safe(fromHeader?.takeIf { it.isNotBlank() } ?: fromUrl ?: "file")
        if (name.substringAfterLast('.', "").isEmpty() || name.startsWith(".")) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            if (ext != null) name = "$name.$ext"
        }
        return name
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        s
    }

    private fun safe(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().trim('.')
        if (cleaned.isEmpty()) return "file"
        if (cleaned.length <= 120) return cleaned
        val ext = cleaned.substringAfterLast('.', "")
        return if (ext.length in 1..8) cleaned.take(110) + "." + ext else cleaned.take(120)
    }
}
