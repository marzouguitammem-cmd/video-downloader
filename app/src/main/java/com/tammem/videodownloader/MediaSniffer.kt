package com.tammem.videodownloader

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Collects the video / audio addresses a web page loads while the user plays it in the
 * in-app browser. Many sites refuse requests that do not come from a real browser; the page
 * itself does not, so whatever it plays can be downloaded with the same cookies and referer.
 */
class MediaSniffer(private val onChange: () -> Unit) {

    enum class Type { HLS, DASH, VIDEO, AUDIO, UNKNOWN }

    data class Media(
        val url: String,
        val type: Type,
        val quality: Int?,
        @Volatile var size: Long = -1,
    )

    private val found = LinkedHashMap<String, Media>()
    private val sizes = Executors.newFixedThreadPool(2)

    /** Header values used to ask each file's size; set by the browser. */
    @Volatile var headers: Map<String, String> = emptyMap()

    /** The browser's cookies for an address. */
    @Volatile var cookies: ((String) -> String?)? = null

    val count: Int get() = synchronized(found) { visible().size }

    /** Best candidates first: streams, then the biggest files. Tiny clips (previews, ads) are hidden. */
    fun list(): List<Media> = synchronized(found) {
        visible().sortedWith(
            compareBy<Media> { order(it.type) }
                .thenByDescending { it.size }
                .thenByDescending { it.quality ?: 0 },
        )
    }

    private fun visible() = found.values.filter { it.size < 0 || it.size >= MIN_SIZE || it.type == Type.AUDIO }

    private fun order(t: Type) = when (t) {
        Type.HLS, Type.DASH -> 0
        Type.VIDEO -> 1
        Type.UNKNOWN -> 2
        Type.AUDIO -> 3
    }

    fun clear() {
        synchronized(found) { found.clear() }
        onChange()
    }

    /** Called for every request the page makes ([hasRange]: a media element asking for bytes). */
    fun consider(url: String, hasRange: Boolean = false) {
        if (!url.startsWith("http")) return
        val type = classify(url, hasRange) ?: return
        val key = url.substringBefore('#')
        val media = synchronized(found) {
            if (found.containsKey(key)) return
            Media(key, type, qualityOf(url)).also { found[key] = it }
        }
        onChange()
        if (type == Type.VIDEO || type == Type.AUDIO || type == Type.UNKNOWN) {
            sizes.execute {
                media.size = headSize(key)
                onChange()
            }
        }
    }

    fun shutdown() = sizes.shutdownNow()

    private fun headSize(url: String): Long = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 10_000
            readTimeout = 10_000
            for ((k, v) in headers) setRequestProperty(k, v)
            cookies?.invoke(url)?.let { setRequestProperty("Cookie", it) }
        }
        try {
            if (c.responseCode in 200..299) c.contentLengthLong else -1L
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        -1L
    }

    companion object {
        private const val MIN_SIZE = 600L * 1024

        private val VIDEO_EXT = setOf("mp4", "m4v", "webm", "mov", "mkv", "flv", "3gp")
        private val AUDIO_EXT = setOf("mp3", "m4a", "ogg", "opus", "wav", "flac")
        private val SEGMENT = Regex("""(?i)(seg[-_]?\d+|/segment|frag(ment)?[-_]?\d+|chunk[-_]?\d+|/range/|init\.mp4)""")
        private val QUALITY = Regex("""(?<![\d])(2160|1440|1080|720|540|480|360|240)[pP]?(?![\d])""")

        fun classify(url: String, hasRange: Boolean): Type? {
            val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').lowercase(Locale.ROOT)
            val ext = path.substringAfterLast('/').substringAfterLast('.', "")
            return when {
                ext == "m3u8" || path.contains(".m3u8") -> Type.HLS
                ext == "mpd" -> Type.DASH
                ext == "ts" || ext == "m4s" || ext == "aac" -> null // pieces of a stream
                SEGMENT.containsMatchIn(path) -> null
                ext in VIDEO_EXT -> Type.VIDEO
                ext in AUDIO_EXT -> Type.AUDIO
                // A <video> asking for "bytes=0-" from an address with no extension.
                hasRange && ext.isEmpty() -> Type.UNKNOWN
                else -> null
            }
        }

        fun qualityOf(url: String): Int? =
            QUALITY.findAll(url.substringBefore('?')).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()

        /** The page script that reports <video>/<audio> sources (blob: addresses cannot be used). */
        const val SCAN_JS = """(function(){var o=[];document.querySelectorAll('video,audio,video source,audio source').forEach(function(e){var s=e.currentSrc||e.src;if(s&&s.indexOf('http')===0)o.push(s);});var m=document.querySelector('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"]');if(m&&m.content&&m.content.indexOf('http')===0)o.push(m.content);return JSON.stringify(o);})()"""
    }
}
