package com.tammem.videodownloader

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tammem.videodownloader.databinding.ActivityLoginBinding

/**
 * Lets the user log into their own TikTok / Instagram / Facebook account once. Only the resulting
 * cookies are kept, in the app's private storage, so yt-dlp can open posts that the site shows to
 * logged-in users only.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding
    private lateinit var site: Site
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        site = intent.getStringExtra(EXTRA_SITE)?.let { name -> Site.entries.firstOrNull { it.name == name } }
            ?: Site.TIKTOK
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        b.toolbar.title = getString(R.string.login_title, getString(site.nameRes))
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.inflateMenu(R.menu.login)
        b.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_done) {
                tryFinish(manual = true); true
            } else {
                false
            }
        }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(b.web, true)

        b.web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Look like the normal Chrome browser rather than an embedded page.
            userAgentString = userAgentString.replace("; wv", "").replace(" Version/4.0", "")
        }
        b.web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                b.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                b.progress.setProgressCompat(newProgress, true)
            }
        }
        b.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Ignore "open in the TikTok app" links (intent://, snssdk://…).
                val scheme = request.url.scheme.orEmpty()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tryFinish(manual = false)
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (b.web.canGoBack()) b.web.goBack() else finish()
        }

        if (savedInstanceState == null) b.web.loadUrl(site.loginUrl)
    }

    /** Finishes as soon as the site has set its session cookies. */
    private fun tryFinish(manual: Boolean) {
        if (finished) return
        val manager = CookieManager.getInstance()
        manager.flush()
        if (Accounts.save(this, site, manager.getCookie(site.homeUrl))) {
            finished = true
            Toast.makeText(this, R.string.login_done, Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        } else if (manual) {
            Toast.makeText(this, R.string.login_not_yet, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        b.web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SITE = "site"
    }
}
