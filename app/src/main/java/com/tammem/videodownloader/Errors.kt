package com.tammem.videodownloader

import android.content.Context

/** Turns yt-dlp's English errors into a short Arabic explanation. */
object Errors {
    fun describe(context: Context, e: Throwable, url: String): DlState.Failed {
        val raw = e.message ?: e.toString()
        val t = raw.lowercase()
        val tiktok = Links.isTikTok(url) || "[tiktok]" in t
        val loginNeeded = "log in" in t || "login" in t || "logged-in" in t || "cookies" in t ||
            "comfortable for some audiences" in t || "permission to view" in t

        var needsLogin = false
        val res = when {
            "unsupported url" in t -> R.string.err_unsupported
            "requested format is not available" in t -> R.string.err_format
            tiktok && loginNeeded -> {
                needsLogin = true
                if (TikTok.isLoggedIn(context)) R.string.err_tiktok_login_again else R.string.err_tiktok_login
            }
            "private video" in t || "video is private" in t -> R.string.err_private
            "confirm your age" in t || "age-restricted" in t || "inappropriate for some users" in t -> R.string.err_age
            "not a bot" in t || "sign in to confirm" in t -> R.string.err_bot
            "ip address is blocked" in t -> R.string.err_unavailable
            "video unavailable" in t || "video is unavailable" in t || "has been removed" in t ||
                "not available in your country" in t || "geo restrict" in t ||
                "video not available" in t -> R.string.err_unavailable
            "unable to resolve" in t || "failed to resolve" in t || "name or service not known" in t ||
                "temporary failure in name resolution" in t || "no address associated" in t ||
                "network is unreachable" in t || "timed out" in t || "connection reset" in t ||
                "connection refused" in t || "unable to connect" in t -> R.string.err_network
            "no space left" in t || "enospc" in t -> R.string.err_space
            "http error 403" in t || "http error 429" in t -> R.string.err_blocked
            loginNeeded -> R.string.err_login
            else -> R.string.err_generic
        }
        val errorLines = raw.lines().filter { it.contains("ERROR") }
        val details = (if (errorLines.isEmpty()) raw else errorLines.joinToString("\n")).take(3000)
        return DlState.Failed(context.getString(res), details, needsLogin)
    }
}
