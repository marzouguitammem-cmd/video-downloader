package com.tammem.videodownloader

import android.content.Context

/** Turns yt-dlp's English errors into a short Arabic explanation. */
object Errors {

    private val LOGIN_HINTS = listOf(
        "log in", "login", "logged-in", "cookies", "registered users", "rate-limit",
        "empty media response", "comfortable for some audiences", "permission to view",
        "not granting access", "restricted video",
    )

    fun describe(context: Context, e: Throwable, req: Downloader.Request): DlState.Failed {
        val raw = e.message ?: e.toString()
        val t = raw.lowercase()
        val site = Site.forUrl(req.url) ?: Site.fromError(raw)
        val siteName = site?.let { context.getString(it.nameRes) }.orEmpty()
        val loggedIn = site != null && Accounts.isLoggedIn(context, site)

        // Facebook says "Cannot parse data" both for posts it hides from visitors and for posts
        // that simply have no video.
        val facebookNoData = site == Site.FACEBOOK && "cannot parse data" in t
        val loginRedirect = "unsupported url" in t && ("/login" in t || "accounts/login" in t)
        val wantsLogin = site != null && (loginRedirect || facebookNoData || LOGIN_HINTS.any { it in t })

        var loginSite: Site? = null
        val message = when {
            "there is no video in this post" in t || "no video formats found" in t -> context.getString(
                if (req.audio) R.string.err_photos_only_audio else R.string.err_no_video,
            )
            "unsupported url" in t && !loginRedirect -> context.getString(R.string.err_unsupported)
            "requested format is not available" in t -> context.getString(R.string.err_format)
            wantsLogin && !loggedIn -> {
                loginSite = site
                context.getString(R.string.err_site_login, siteName)
            }
            facebookNoData -> context.getString(R.string.err_fb_no_video)
            wantsLogin -> {
                loginSite = site
                context.getString(R.string.err_site_login_again, siteName)
            }
            "private video" in t || "video is private" in t -> context.getString(R.string.err_private)
            "confirm your age" in t || "age-restricted" in t || "inappropriate for some users" in t ->
                context.getString(R.string.err_age)
            "not a bot" in t || "sign in to confirm" in t -> context.getString(R.string.err_bot)
            "ip address is blocked" in t || "video unavailable" in t || "video is unavailable" in t ||
                "has been removed" in t || "not available in your country" in t || "geo restrict" in t ||
                "video not available" in t || "is not available" in t -> context.getString(R.string.err_unavailable)
            "unable to resolve" in t || "failed to resolve" in t || "name or service not known" in t ||
                "temporary failure in name resolution" in t || "no address associated" in t ||
                "network is unreachable" in t || "timed out" in t || "connection reset" in t ||
                "connection refused" in t || "unable to connect" in t -> context.getString(R.string.err_network)
            "no space left" in t || "enospc" in t -> context.getString(R.string.err_space)
            "http error 403" in t || "http error 429" in t -> context.getString(R.string.err_blocked)
            "log in" in t || "login" in t || "cookies" in t -> context.getString(R.string.err_login)
            else -> context.getString(R.string.err_generic)
        }
        val errorLines = raw.lines().filter { it.contains("ERROR") }
        val details = (if (errorLines.isEmpty()) raw else errorLines.joinToString("\n")).take(3000)
        return DlState.Failed(message, details, loginSite)
    }
}
