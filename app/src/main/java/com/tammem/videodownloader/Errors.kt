package com.tammem.videodownloader

import android.content.Context

/** Turns yt-dlp's English errors into a short Arabic explanation. */
object Errors {
    fun describe(context: Context, e: Throwable): DlState.Failed {
        val raw = e.message ?: e.toString()
        val t = raw.lowercase()
        val res = when {
            "unsupported url" in t -> R.string.err_unsupported
            "requested format is not available" in t -> R.string.err_format
            "private video" in t || "video is private" in t -> R.string.err_private
            "confirm your age" in t || "age-restricted" in t || "inappropriate for some users" in t -> R.string.err_age
            "not a bot" in t || "sign in to confirm" in t -> R.string.err_bot
            "video unavailable" in t || "video is unavailable" in t || "has been removed" in t ||
                "not available in your country" in t || "geo restrict" in t -> R.string.err_unavailable
            "unable to resolve" in t || "failed to resolve" in t || "name or service not known" in t ||
                "temporary failure in name resolution" in t || "no address associated" in t ||
                "network is unreachable" in t || "timed out" in t || "connection reset" in t ||
                "connection refused" in t || "unable to connect" in t -> R.string.err_network
            "no space left" in t || "enospc" in t -> R.string.err_space
            "http error 403" in t || "http error 429" in t -> R.string.err_blocked
            "login" in t || "log in" in t || "logged-in" in t || "cookies" in t -> R.string.err_login
            else -> R.string.err_generic
        }
        val errorLines = raw.lines().filter { it.contains("ERROR") }
        val details = (if (errorLines.isEmpty()) raw else errorLines.joinToString("\n")).take(3000)
        return DlState.Failed(context.getString(res), details)
    }
}
