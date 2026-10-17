package com.tammem.videodownloader

object Links {
    private val FULL = Regex("""https?://[^\s<>"'«»]+""", RegexOption.IGNORE_CASE)
    private val BARE = Regex(
        """(?:(?:www|m|vm|vt)\.)?(?:youtube\.com|youtu\.be|tiktok\.com)/[^\s<>"'«»]+""",
        RegexOption.IGNORE_CASE,
    )
    private const val TRAILING = ".,;:!?)]}،؟"

    /** Finds the first link in any text (TikTok and YouTube share a sentence + link). */
    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null
        FULL.find(text)?.let { return it.value.trimEnd(*TRAILING.toCharArray()) }
        BARE.find(text)?.let { return "https://" + it.value.trimEnd(*TRAILING.toCharArray()) }
        return null
    }

    fun isTikTok(url: String): Boolean = url.contains("tiktok.com", ignoreCase = true)

    /** Used only to decide whether to auto-fill from the clipboard. */
    fun isKnownSite(url: String): Boolean =
        isTikTok(url) ||
            url.contains("youtube.com", ignoreCase = true) ||
            url.contains("youtu.be", ignoreCase = true)
}
