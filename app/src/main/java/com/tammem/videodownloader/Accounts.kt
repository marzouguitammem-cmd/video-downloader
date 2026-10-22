package com.tammem.videodownloader

import android.content.Context
import android.webkit.CookieManager
import java.io.File

/** The sites whose posts sometimes need the user to be logged in. */
enum class Site(
    val nameRes: Int,
    /** Cookie domain, also used to recognise links. */
    val domain: String,
    val loginUrl: String,
    val homeUrl: String,
    /** Kept as "tiktok_cookies.txt" so the TikTok login from older versions still works. */
    val cookieFileName: String,
) {
    TIKTOK(
        R.string.site_tiktok, "tiktok.com",
        "https://www.tiktok.com/login", "https://www.tiktok.com",
        "tiktok_cookies.txt",
    ),
    INSTAGRAM(
        R.string.site_instagram, "instagram.com",
        "https://www.instagram.com/accounts/login/", "https://www.instagram.com",
        "instagram_cookies.txt",
    ),
    FACEBOOK(
        R.string.site_facebook, "facebook.com",
        "https://m.facebook.com/login/", "https://www.facebook.com",
        "facebook_cookies.txt",
    ),
    ;

    /** True once the site has set the cookies that mean "logged in". */
    fun hasSession(names: Set<String>): Boolean = when (this) {
        TIKTOK -> "sessionid" in names || "sessionid_ss" in names
        INSTAGRAM -> "sessionid" in names
        FACEBOOK -> "c_user" in names && "xs" in names
    }

    companion object {
        private val HOSTS = mapOf(
            TIKTOK to Regex("""(^|[/.])tiktok\.com(/|$|\?)""", RegexOption.IGNORE_CASE),
            INSTAGRAM to Regex("""(^|[/.])(instagram\.com|instagr\.am)(/|$|\?)""", RegexOption.IGNORE_CASE),
            FACEBOOK to Regex("""(^|[/.])(facebook\.com|fb\.watch|fb\.com)(/|$|\?)""", RegexOption.IGNORE_CASE),
        )

        fun forUrl(url: String): Site? {
            val host = url.substringAfter("://").substringBefore('/').substringBefore('?')
            return entries.firstOrNull { HOSTS.getValue(it).containsMatchIn("/$host/") }
        }

        /** yt-dlp prefixes its errors with the extractor: "[TikTok]", "[Instagram]", "[facebook]"… */
        fun fromError(text: String): Site? {
            val t = text.lowercase()
            return when {
                "[tiktok" in t || "[vm.tiktok" in t -> TIKTOK
                "[instagram" in t -> INSTAGRAM
                "[facebook" in t -> FACEBOOK
                else -> null
            }
        }
    }
}

/** Login cookies, kept in the app's private storage and handed to yt-dlp as a cookies.txt file. */
object Accounts {

    fun cookieFile(context: Context, site: Site) = File(context.filesDir, site.cookieFileName)

    fun isLoggedIn(context: Context, site: Site) = cookieFile(context, site).exists()

    /** Saves the WebView's cookies for [site]. Returns false if the user is not logged in yet. */
    fun save(context: Context, site: Site, cookieHeader: String?): Boolean {
        val text = netscapeCookies(site, cookieHeader) ?: return false
        cookieFile(context, site).writeText(text)
        return true
    }

    /** "a=1; b=2" → Netscape cookies.txt, or null when there is no session cookie. */
    fun netscapeCookies(site: Site, cookieHeader: String?): String? {
        val pairs = parse(cookieHeader)
        if (!site.hasSession(pairs.map { it.first }.toSet())) return null
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        return buildString {
            append("# Netscape HTTP Cookie File\n")
            for ((name, value) in pairs) {
                append('.').append(site.domain).append("\tTRUE\t/\tTRUE\t").append(expiry)
                    .append('\t').append(name).append('\t').append(value).append('\n')
            }
        }
    }

    /** The saved cookies as a "Cookie:" header, for following share links. */
    fun cookieHeader(context: Context, site: Site): String? {
        val file = cookieFile(context, site)
        if (!file.exists()) return null
        return try {
            file.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val cols = line.split('\t')
                    if (cols.size >= 7) "${cols[5]}=${cols[6]}" else null
                }
                .joinToString("; ")
                .ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    fun logout(context: Context, site: Site) {
        cookieFile(context, site).delete()
        // Forget this site in the login browser too, without touching the other sites.
        try {
            val manager = CookieManager.getInstance()
            for ((name, _) in parse(manager.getCookie(site.homeUrl))) {
                manager.setCookie(site.homeUrl, "$name=; Max-Age=0; Path=/; Domain=.${site.domain}")
                manager.setCookie(site.homeUrl, "$name=; Max-Age=0; Path=/")
            }
            manager.flush()
        } catch (e: Exception) {
            // WebView unavailable: the cookie file is gone anyway.
        }
    }

    private fun parse(cookieHeader: String?): List<Pair<String, String>> =
        cookieHeader.orEmpty().split(';').mapNotNull { part ->
            val p = part.trim()
            val i = p.indexOf('=')
            if (i <= 0) null else p.substring(0, i).trim() to p.substring(i + 1).trim()
        }
}
