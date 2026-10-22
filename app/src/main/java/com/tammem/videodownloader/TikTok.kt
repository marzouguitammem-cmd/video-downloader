package com.tammem.videodownloader

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** TikTok-specific helpers: short links and photo (slideshow) posts. */
object TikTok {
    private val SHORT = Regex(
        """^https?://(?:vm\.tiktok\.com|vt\.tiktok\.com|(?:www\.)?tiktok\.com/t)/""",
        RegexOption.IGNORE_CASE,
    )
    private val PHOTO = Regex("""tiktok\.com/@([^/?#\s]+)/photo/(\d+)""", RegexOption.IGNORE_CASE)

    data class PhotoPost(val user: String, val id: String) {
        /** yt-dlp only understands /video/ links; TikTok serves the same post there. */
        val videoUrl: String get() = "https://www.tiktok.com/@$user/video/$id"
    }

    data class Slideshow(val title: String?, val imageUrls: List<String>)

    fun isShortLink(url: String): Boolean = SHORT.containsMatchIn(url)

    /** Finds a photo-post link anywhere in [text] (a URL or a yt-dlp error message). */
    fun photoPost(text: String): PhotoPost? =
        PHOTO.find(text)?.let { PhotoPost(it.groupValues[1], it.groupValues[2]) }

    // ───────────────────────── Photo posts ─────────────────────────

    /** yt-dlp's --dump-pages prints each downloaded page as one long base64 line. */
    fun isPageDump(line: String): Boolean {
        if (line.length < 2000) return false
        for (c in line) {
            val ok = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '+' || c == '/' || c == '='
            if (!ok) return false
        }
        return true
    }

    fun parseSlideshow(pages: List<String>): Slideshow? {
        for (page in pages) {
            val html = try {
                String(Base64.decode(page, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                continue
            }
            parseHtml(html)?.let { return it }
        }
        return null
    }

    /** Separate from [parseSlideshow] so it can be tested without Android. */
    fun parseHtml(html: String): Slideshow? = fromUniversalData(html) ?: fromRawScan(html)

    private fun scriptContent(html: String, id: String): String? {
        val marker = html.indexOf("id=\"$id\"")
        if (marker < 0) return null
        val start = html.indexOf('>', marker) + 1
        if (start <= 0) return null
        val end = html.indexOf("</script>", start)
        if (end < 0) return null
        return html.substring(start, end)
    }

    private fun fromUniversalData(html: String): Slideshow? {
        val json = scriptContent(html, "__UNIVERSAL_DATA_FOR_REHYDRATION__") ?: return null
        val item = try {
            JSONObject(json).optJSONObject("__DEFAULT_SCOPE__")
                ?.optJSONObject("webapp.video-detail")
                ?.optJSONObject("itemInfo")
                ?.optJSONObject("itemStruct")
        } catch (e: Exception) {
            null
        } ?: return null
        val images = item.optJSONObject("imagePost")?.optJSONArray("images") ?: return null
        val urls = (0 until images.length()).mapNotNull { i ->
            pickUrl(images.optJSONObject(i)?.optJSONObject("imageURL")?.optJSONArray("urlList"))
        }
        if (urls.isEmpty()) return null
        return Slideshow(item.optString("desc").takeIf { it.isNotBlank() }, urls)
    }

    private fun pickUrl(list: JSONArray?): String? {
        list ?: return null
        val all = (0 until list.length()).map { list.optString(it) }.filter { it.startsWith("http") }
        return choose(all)
    }

    private fun choose(all: List<String>): String? =
        all.firstOrNull { it.contains(".jpeg?") } ?: all.firstOrNull { it.contains("jpeg") } ?: all.firstOrNull()

    private val IMAGE_URL_LIST = Regex(""""imageURL"\s*:\s*\{\s*"urlList"\s*:\s*\[([^\]]*)]""")
    private val QUOTED_URL = Regex(""""(https?:[^"]+)"""")

    /** Fallback for older page formats: scan the raw text of the photo-post section. */
    private fun fromRawScan(html: String): Slideshow? {
        val start = html.indexOf("\"imagePost\"")
        if (start < 0) return null
        val section = html.substring(start, minOf(html.length, start + 300_000))
        val seen = HashSet<String>()
        val urls = IMAGE_URL_LIST.findAll(section).mapNotNull { m ->
            val list = QUOTED_URL.findAll(m.groupValues[1]).map { unescape(it.groupValues[1]) }.toList()
            choose(list)
        }.filter { seen.add(it.substringBefore('?')) }.toList()
        return if (urls.isEmpty()) null else Slideshow(null, urls)
    }

    private fun unescape(s: String): String =
        s.replace("\\u002F", "/").replace("\\u0026", "&").replace("\\/", "/")

    /** Downloads one slideshow image into [dir]; the extension follows the server's content type. */
    fun downloadImage(url: String, dir: File, baseName: String): File {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", Links.USER_AGENT)
            setRequestProperty("Referer", "https://www.tiktok.com/")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code while downloading image")
            val type = conn.contentType.orEmpty().lowercase()
            val ext = when {
                "png" in type -> "png"
                "webp" in type -> "webp"
                "heic" in type || "heif" in type -> "heic"
                else -> "jpg"
            }
            val file = File(dir, "$baseName.$ext")
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            return file
        } finally {
            conn.disconnect()
        }
    }
}
