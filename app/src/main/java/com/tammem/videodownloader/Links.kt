package com.tammem.videodownloader

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

object Links {
    private val FULL = Regex("""https?://[^\s<>"'«»]+""", RegexOption.IGNORE_CASE)
    private val BARE = Regex(
        """(?:(?:www|m|web|vm|vt)\.)?(?:youtube\.com|youtu\.be|tiktok\.com|instagram\.com|facebook\.com|fb\.watch)/[^\s<>"'«»]+""",
        RegexOption.IGNORE_CASE,
    )
    private const val TRAILING = ".,;:!?)]}،؟"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/129.0.0.0 Mobile Safari/537.36"

    /** Finds the first link in any text (the apps share a sentence + link). */
    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null
        FULL.find(text)?.let { return it.value.trimEnd(*TRAILING.toCharArray()) }
        BARE.find(text)?.let { return "https://" + it.value.trimEnd(*TRAILING.toCharArray()) }
        return null
    }

    fun isTikTok(url: String): Boolean = Site.forUrl(url) == Site.TIKTOK

    fun isInstagram(url: String): Boolean = Site.forUrl(url) == Site.INSTAGRAM

    fun isYouTube(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').lowercase()
        return host == "youtu.be" || host.endsWith("youtube.com")
    }

    // ───────────────────────── Share links ─────────────────────────

    private val FACEBOOK_SHARE = Regex(
        """^https?://(?:fb\.watch/|(?:[\w-]+\.)?facebook\.com/share/)""",
        RegexOption.IGNORE_CASE,
    )
    private val INSTAGRAM_SHARE = Regex(
        """^https?://(?:www\.)?instagram\.com/share/""",
        RegexOption.IGNORE_CASE,
    )

    /** Facebook and Instagram "share" links that hide the real post address. */
    fun isShareLink(url: String): Boolean =
        FACEBOOK_SHARE.containsMatchIn(url) || INSTAGRAM_SHARE.containsMatchIn(url)

    /**
     * Follows redirects (and login pages that carry the real address in "next=") to the post.
     * Blocking; returns null when nothing better than [url] was found.
     */
    fun resolve(url: String, cookieHeader: String? = null): String? {
        var current = url
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                setRequestProperty("Accept-Language", "ar,fr;q=0.9,en;q=0.8")
                if (!cookieHeader.isNullOrBlank()) setRequestProperty("Cookie", cookieHeader)
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return null
                    val next = URL(URL(current), location).toString()
                    loginTarget(next)?.let { return it.takeIf { t -> !isShareLink(t) } }
                    current = next
                    if (!isShareLink(current) && !TikTok.isShortLink(current)) return current
                } else {
                    if (code !in 200..299) return null
                    // Some pages answer 200 and name the real post in their meta tags.
                    val html = conn.inputStream.use { input ->
                        val buf = ByteArray(512 * 1024)
                        var n = 0
                        while (n < buf.size) {
                            val r = input.read(buf, n, buf.size - n)
                            if (r < 0) break
                            n += r
                        }
                        String(buf, 0, n, Charsets.UTF_8)
                    }
                    val found = metaUrl(html)?.takeIf { !isShareLink(it) && Site.forUrl(it) != null }
                    return found ?: if (current != url) current else null
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private val OG_URL = Regex("""<meta[^>]+property=["']og:url["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OG_URL_REV = Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:url["']""", RegexOption.IGNORE_CASE)
    private val CANONICAL = Regex("""<link[^>]+rel=["']canonical["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private fun metaUrl(html: String): String? {
        val raw = OG_URL.find(html)?.groupValues?.get(1)
            ?: OG_URL_REV.find(html)?.groupValues?.get(1)
            ?: CANONICAL.find(html)?.groupValues?.get(1)
            ?: return null
        return raw.replace("&amp;", "&").takeIf { it.startsWith("http") }
    }

    /** "https://www.facebook.com/login/?next=https%3A%2F%2F…" → the address after "next=". */
    fun loginTarget(url: String): String? {
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?')
        val isLogin = path.startsWith("login") || path.startsWith("accounts/login")
        if (!isLogin) return null
        val query = url.substringAfter('?', "")
        val next = query.split('&').firstOrNull { it.startsWith("next=") }?.substringAfter("next=") ?: return null
        val decoded = try {
            URLDecoder.decode(next, "UTF-8")
        } catch (e: Exception) {
            return null
        }
        return when {
            decoded.startsWith("http") -> decoded
            decoded.startsWith("/") -> URL(URL(url), decoded).toString()
            else -> null
        }
    }
}
