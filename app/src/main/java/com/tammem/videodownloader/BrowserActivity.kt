package com.tammem.videodownloader

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tammem.videodownloader.databinding.ActivityBrowserBinding
import org.json.JSONArray
import java.util.Locale

/**
 * A small browser: the user opens the page and plays the video; every video the page loads is
 * listed under the "تحميل" button and downloaded with the page's cookies, so sites that refuse
 * direct downloads still work. Files the page offers for download are caught too.
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var b: ActivityBrowserBinding
    private lateinit var sniffer: MediaSniffer
    private var userAgent: String = Links.USER_AGENT

    private val scan = object : Runnable {
        override fun run() {
            b.web.evaluateJavascript(MediaSniffer.SCAN_JS) { result ->
                try {
                    val json = JSONArray(JSONArray("[$result]").getString(0))
                    for (i in 0 until json.length()) sniffer.consider(json.getString(i), hasRange = true)
                } catch (e: Exception) {
                    // Page without media, or still loading.
                }
            }
            b.web.postDelayed(this, 2500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        sniffer = MediaSniffer { runOnUiThread { refreshButton() } }
        sniffer.cookies = { url -> CookieManager.getInstance().getCookie(url) }

        b.toolbar.setNavigationOnClickListener { finish() }
        b.address.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                go(b.address.text?.toString().orEmpty()); true
            } else {
                false
            }
        }
        b.downloadFab.setOnClickListener { chooseMedia() }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(b.web, true)

        b.web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            // Pop-up windows (mostly ads) are refused below.
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            // Look like the normal Chrome browser rather than an embedded page.
            userAgentString = userAgentString.replace("; wv", "").replace(" Version/4.0", "")
            userAgent = userAgentString
        }

        b.web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                b.progress.isVisible = newProgress in 1..99
                b.progress.setProgressCompat(newProgress, true)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                b.toolbar.title = title?.takeIf { it.isNotBlank() } ?: getString(R.string.browser_title)
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
        }

        b.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Ignore "open in app" / store links (intent://, market://…).
                val scheme = request.url.scheme.orEmpty()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                sniffer.clear()
                sniffer.headers = mapOf("User-Agent" to userAgent, "Referer" to url.orEmpty())
                b.address.setText(url.orEmpty())
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                val hasRange = request.requestHeaders.keys.any { it.equals("Range", ignoreCase = true) }
                sniffer.consider(request.url.toString(), hasRange)
                return null
            }
        }

        // A download link on the page ("تحميل" buttons, PDFs…): hand it over as a file.
        b.web.setDownloadListener { url, _, _, _, _ ->
            if (url.startsWith("http")) finishWith(url, direct = true)
        }

        onBackPressedDispatcher.addCallback(this) {
            if (b.web.canGoBack()) b.web.goBack() else finish()
        }

        refreshButton()
        if (savedInstanceState == null) {
            go(intent.getStringExtra(EXTRA_URL) ?: "https://www.google.com")
        } else {
            b.web.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        b.web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        b.web.onResume()
        b.web.post(scan)
    }

    override fun onPause() {
        b.web.removeCallbacks(scan)
        b.web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        sniffer.shutdown()
        b.web.destroy()
        super.onDestroy()
    }

    private fun go(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = Links.extract(text)
            ?: if (!text.contains(' ') && text.contains('.')) "https://$text"
            else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(text, "UTF-8")
        WindowCompat.getInsetsController(window, b.root).hide(WindowInsetsCompat.Type.ime())
        b.address.clearFocus()
        b.web.loadUrl(url)
    }

    private fun refreshButton() {
        val n = sniffer.count
        b.downloadFab.isVisible = n > 0
        b.downloadFab.text = if (n == 1) getString(R.string.browser_download_one) else getString(R.string.browser_download_n, n)
        b.hint.isVisible = n == 0
    }

    private fun chooseMedia() {
        val items = sniffer.list()
        if (items.isEmpty()) return
        if (items.size == 1) {
            finishWith(items[0].url, direct = false)
            return
        }
        val labels = items.map { label(it) }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browser_choose)
            .setItems(labels) { _, which -> finishWith(items[which].url, direct = false) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun label(m: MediaSniffer.Media): String {
        val kind = getString(
            when (m.type) {
                MediaSniffer.Type.HLS, MediaSniffer.Type.DASH -> R.string.media_stream
                MediaSniffer.Type.VIDEO -> R.string.media_video
                MediaSniffer.Type.AUDIO -> R.string.media_audio
                MediaSniffer.Type.UNKNOWN -> R.string.media_unknown
            },
        )
        val parts = mutableListOf(kind)
        m.quality?.let { parts += "${it}p" }
        if (m.size > 0) parts += String.format(Locale.US, "%.1f MB", m.size / 1_048_576.0)
        val host = m.url.substringAfter("://").substringBefore('/')
        return parts.joinToString(" • ") + "\n" + host
    }

    /** Returns the chosen address to the main screen, which downloads it. */
    private fun finishWith(mediaUrl: String, direct: Boolean) {
        val page = b.web.url
        val cookies = CookieManager.getInstance().getCookie(mediaUrl)
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(RESULT_URL, mediaUrl)
                .putExtra(RESULT_REFERER, page)
                .putExtra(RESULT_USER_AGENT, userAgent)
                .putExtra(RESULT_COOKIES, cookies)
                .putExtra(RESULT_TITLE, if (direct) null else b.web.title)
                .putExtra(RESULT_DIRECT, direct),
        )
        finish()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val RESULT_URL = "media_url"
        const val RESULT_REFERER = "referer"
        const val RESULT_USER_AGENT = "user_agent"
        const val RESULT_COOKIES = "cookies"
        const val RESULT_TITLE = "title"
        const val RESULT_DIRECT = "direct"
    }
}
