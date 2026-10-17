package com.tammem.videodownloader

import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** TikTok-specific helpers: short links, photo (slideshow) posts and the login cookies. */
object TikTok {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/129.0.0.0 Mobile Safari/537.36"

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

    /** Follows vm./vt.tiktok.com redirects to the full link. Blocking; returns null on failure. */
    fun resolve(url: String): String? {
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = conn.responseCode
                if (code !in 300..399) return if (current != url) current else null
                val location = conn.getHeaderField("Location") ?: return null
                current = URL(URL(current), location).toString()
                if (!isShortLink(current)) return current
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

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
            setRequestProperty("User-Agent", USER_AGENT)
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

    // ───────────────────────── Login cookies ─────────────────────────

    fun cookieFile(context: Context) = File(context.filesDir, "tiktok_cookies.txt")

    fun isLoggedIn(context: Context) = cookieFile(context).exists()

    /** Saves the WebView's TikTok cookies for yt-dlp. Returns false if the user is not logged in yet. */
    fun saveCookies(context: Context, cookieHeader: String?): Boolean {
        val text = netscapeCookies(cookieHeader) ?: return false
        cookieFile(context).writeText(text)
        return true
    }

    /** "a=1; b=2" → Netscape cookies.txt, or null when there is no session cookie. */
    fun netscapeCookies(cookieHeader: String?): String? {
        val pairs = cookieHeader.orEmpty().split(';').mapNotNull { part ->
            val p = part.trim()
            val i = p.indexOf('=')
            if (i <= 0) null else p.substring(0, i).trim() to p.substring(i + 1).trim()
        }
        if (pairs.none { it.first == "sessionid" || it.first == "sessionid_ss" }) return null
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        return buildString {
            append("# Netscape HTTP Cookie File\n")
            for ((name, value) in pairs) {
                append(".tiktok.com\tTRUE\t/\tTRUE\t").append(expiry)
                    .append('\t').append(name).append('\t').append(value).append('\n')
            }
        }
    }

    fun logout(context: Context) {
        cookieFile(context).delete()
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // WebView unavailable: the cookie file is gone anyway.
        }
    }
}
