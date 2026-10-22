package com.tammem.videodownloader

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tammem.videodownloader.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private var pending: Downloader.Request? = null
    private var lastAutoPasted: String? = null
    private var autoPasteArmed = false

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val req = pending
            pending = null
            if (granted && req != null) askNotificationsThenStart(req) else toast(R.string.need_storage)
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            val req = pending
            pending = null
            if (req != null) launchDownload(req)
        }

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // Logged in after a "login needed" error: try the same link again right away.
            val s = Downloader.state.value
            if (result.resultCode == RESULT_OK && s is DlState.Failed && s.loginSite != null) {
                Downloader.lastRequest?.let { startWithPermissions(it) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupToolbar()
        setupForm()
        observe()
        b.footerPlace.text = getString(R.string.saved_in, Saver.displayPath)

        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        autoPasteArmed = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only lets apps read the clipboard once they have focus.
        if (hasFocus && autoPasteArmed) {
            autoPasteArmed = false
            if (b.urlInput.text.isNullOrBlank() && !Downloader.isRunning) paste(manual = false)
        }
    }

    // ───────────────────────────── Setup ─────────────────────────────

    private fun setupToolbar() {
        b.toolbar.inflateMenu(R.menu.main)
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_accounts -> {
                    showAccounts(); true
                }
                R.id.action_update -> {
                    updateEngine(); true
                }
                R.id.action_where -> {
                    showDialog(getString(R.string.where_files), getString(R.string.where_files_text, Saver.displayPath))
                    true
                }
                R.id.action_about -> {
                    showDialog(getString(R.string.about), getString(R.string.about_text, BuildConfig.VERSION_NAME))
                    true
                }
                else -> false
            }
        }
    }

    private fun openLogin(site: Site) =
        loginLauncher.launch(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_SITE, site.name))

    /** TikTok / Instagram / Facebook: shows which ones are logged in; tap to log in or out. */
    private fun showAccounts() {
        val sites = Site.entries
        val labels = sites.map { site ->
            val name = getString(site.nameRes)
            getString(if (Accounts.isLoggedIn(this, site)) R.string.account_logged_in else R.string.account_logged_out, name)
        }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.accounts_title)
            .setItems(labels) { _, which ->
                val site = sites[which]
                if (Accounts.isLoggedIn(this, site)) confirmLogout(site) else openLogin(site)
            }
            .setNegativeButton(R.string.close, null)
            .show()
        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun confirmLogout(site: Site) {
        val name = getString(site.nameRes)
        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.logout_confirm, name))
            .setPositiveButton(R.string.logout_action) { _, _ ->
                Accounts.logout(this, site)
                Toast.makeText(this, getString(R.string.logout_done, name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun setupForm() {
        b.formatGroup.check(if (prefs.getBoolean(KEY_AUDIO, false)) R.id.btnAudio else R.id.btnVideo)
        b.qualityGroup.check(
            when (prefs.getString(KEY_QUALITY, Quality.BEST.name)) {
                Quality.P720.name -> R.id.chip720
                Quality.P480.name -> R.id.chip480
                else -> R.id.chipBest
            },
        )
        refreshQualityVisibility()

        b.formatGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                prefs.edit().putBoolean(KEY_AUDIO, checkedId == R.id.btnAudio).apply()
                refreshQualityVisibility()
            }
        }
        b.qualityGroup.setOnCheckedStateChangeListener { _, _ ->
            prefs.edit().putString(KEY_QUALITY, selectedQuality().name).apply()
        }

        b.pasteButton.setOnClickListener { paste(manual = true) }
        b.downloadButton.setOnClickListener { onDownloadClicked() }
        b.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                onDownloadClicked(); true
            } else {
                false
            }
        }
        b.cancelButton.setOnClickListener { Downloader.cancel() }
        b.openButton.setOnClickListener { openResult() }
        b.shareButton.setOnClickListener { shareResult() }
        b.newButton.setOnClickListener {
            Downloader.reset()
            b.urlInput.setText("")
            b.urlInput.requestFocus()
        }
        b.retryButton.setOnClickListener { Downloader.lastRequest?.let { startWithPermissions(it) } }
        b.loginButton.setOnClickListener {
            (Downloader.state.value as? DlState.Failed)?.loginSite?.let { openLogin(it) }
        }
        b.detailsButton.setOnClickListener { b.errorDetails.isVisible = !b.errorDetails.isVisible }
    }

    private fun isAudioSelected() = b.formatGroup.checkedButtonId == R.id.btnAudio

    private fun selectedQuality(): Quality = when (b.qualityGroup.checkedChipId) {
        R.id.chip720 -> Quality.P720
        R.id.chip480 -> Quality.P480
        else -> Quality.BEST
    }

    private fun refreshQualityVisibility() {
        b.qualitySection.isVisible = !isAudioSelected()
    }

    // ───────────────────────────── Input ─────────────────────────────

    private fun handleShare(intent: Intent?) {
        intent ?: return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val link = Links.extract(text)
        if (link == null) {
            toast(R.string.no_link_in_share)
            return
        }
        lastAutoPasted = link
        b.urlInput.setText(link)
        b.urlLayout.error = null
        if (!Downloader.isRunning) Downloader.reset()
    }

    private fun paste(manual: Boolean) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val text = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (e: Exception) {
            null
        }
        val link = Links.extract(text)
        if (link == null) {
            if (manual) toast(R.string.clipboard_empty)
            return
        }
        if (!manual && link == lastAutoPasted) return
        lastAutoPasted = link
        b.urlInput.setText(link)
        b.urlInput.setSelection(link.length)
        b.urlLayout.error = null
    }

    private fun onDownloadClicked() {
        val link = Links.extract(b.urlInput.text?.toString())
        if (link == null) {
            b.urlLayout.error = getString(R.string.err_no_link)
            return
        }
        b.urlLayout.error = null
        if (b.urlInput.text?.toString() != link) b.urlInput.setText(link)
        WindowCompat.getInsetsController(window, b.root).hide(WindowInsetsCompat.Type.ime())
        b.urlInput.clearFocus()
        startWithPermissions(Downloader.Request(link, isAudioSelected(), selectedQuality()))
    }

    // ─────────────────────────── Permissions ───────────────────────────

    private fun startWithPermissions(req: Downloader.Request) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            pending = req
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        askNotificationsThenStart(req)
    }

    private fun askNotificationsThenStart(req: Downloader.Request) {
        if (Build.VERSION.SDK_INT >= 33 &&
            !granted(Manifest.permission.POST_NOTIFICATIONS) &&
            !prefs.getBoolean(KEY_ASKED_NOTIF, false)
        ) {
            prefs.edit().putBoolean(KEY_ASKED_NOTIF, true).apply()
            pending = req
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchDownload(req)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun launchDownload(req: Downloader.Request) {
        if (!Downloader.start(this, req)) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java))
        } catch (e: Exception) {
            // The download still runs while the app is open.
        }
    }

    // ───────────────────────────── State ─────────────────────────────

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Downloader.state.collect { render(it) } }
                launch { Engine.state.collect { renderEngine(it) } }
            }
        }
    }

    private fun render(s: DlState) {
        val running = s is DlState.Running
        b.progressCard.isVisible = running
        b.doneCard.isVisible = s is DlState.Done
        b.errorCard.isVisible = s is DlState.Failed

        b.downloadButton.isEnabled = !running
        b.pasteButton.isEnabled = !running
        b.urlLayout.isEnabled = !running
        b.btnVideo.isEnabled = !running
        b.btnAudio.isEnabled = !running
        for (i in 0 until b.qualityGroup.childCount) b.qualityGroup.getChildAt(i).isEnabled = !running

        when (s) {
            is DlState.Running -> {
                b.progressTitle.text = s.title ?: Downloader.lastRequest?.url ?: ""
                b.progressStatus.text = Notifications.stageText(this, s)
                val determinate = s.determinate
                if (b.progressBar.isIndeterminate == determinate) {
                    b.progressBar.visibility = View.INVISIBLE
                    b.progressBar.isIndeterminate = !determinate
                    b.progressBar.visibility = View.VISIBLE
                }
                if (determinate) b.progressBar.setProgressCompat(s.percent.toInt(), true)
                b.progressPercent.text =
                    if (determinate) String.format(Locale.US, "%.0f%%", s.percent) else ""
                b.progressEta.text =
                    if (determinate && s.etaSeconds > 0) getString(R.string.eta, formatEta(s.etaSeconds)) else ""
            }
            is DlState.Done -> {
                b.doneName.text = if (s.files.size == 1) s.title else doneSummary(s) + "\n" + s.title
                b.donePlace.text = getString(R.string.saved_in, Saver.displayPath)
            }
            is DlState.Failed -> {
                b.errorMessage.text = s.message
                b.errorDetails.text = s.details
                b.errorDetails.isVisible = false
                b.detailsButton.isVisible = s.details.isNotBlank()
                val site = s.loginSite
                b.loginButton.isVisible = site != null
                b.retryButton.isVisible = site == null
                if (site != null) b.loginButton.text = getString(R.string.login_button, getString(site.nameRes))
            }
            DlState.Idle -> Unit
        }
    }

    /** "الصور: 5 • الفيديوهات: 2 • الصوت: 1" */
    private fun doneSummary(s: DlState.Done): String = listOfNotNull(
        s.count("image/").takeIf { it > 0 }?.let { getString(R.string.done_photos, it) },
        s.count("video/").takeIf { it > 0 }?.let { getString(R.string.done_videos, it) },
        s.count("audio/").takeIf { it > 0 }?.let { getString(R.string.done_audio, it) },
    ).joinToString(" • ")

    private fun renderEngine(s: EngineState) {
        b.engineStatus.text = when (s) {
            EngineState.Starting -> getString(R.string.engine_starting)
            EngineState.Updating -> getString(R.string.engine_updating)
            is EngineState.Ready -> s.version?.let { getString(R.string.engine_ready, it) }
                ?: getString(R.string.engine_ready_plain)
            is EngineState.Failed -> getString(R.string.engine_failed, s.message.take(120))
        }
    }

    private fun formatEta(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val sec = seconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        } else {
            String.format(Locale.US, "%d:%02d", m, sec)
        }
    }

    // ───────────────────────────── Actions ─────────────────────────────

    private fun openResult() {
        val s = Downloader.state.value as? DlState.Done ?: return
        val first = s.files.firstOrNull() ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(first.uri, first.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_app_to_open)
        }
    }

    private fun shareResult() {
        val s = Downloader.state.value as? DlState.Done ?: return
        val first = s.files.firstOrNull() ?: return
        val intent: Intent
        if (s.files.size > 1) {
            val kinds = s.files.map { it.mime.substringBefore('/') }.toSet()
            intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            intent.type = if (kinds.size == 1) kinds.first() + "/*" else "*/*"
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(s.files.map { it.uri }))
        } else {
            intent = Intent(Intent.ACTION_SEND)
            intent.type = first.mime
            intent.putExtra(Intent.EXTRA_STREAM, first.uri)
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    private fun updateEngine() {
        if (Downloader.isRunning) {
            toast(R.string.wait_for_download)
            return
        }
        toast(R.string.update_started)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { Engine.updateNow(applicationContext) }
            toast(
                when (result) {
                    true -> R.string.update_done
                    false -> R.string.update_none
                    null -> R.string.update_failed
                },
            )
        }
    }

    private fun showDialog(title: String, message: String) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_AUDIO = "audio"
        private const val KEY_QUALITY = "quality"
        private const val KEY_ASKED_NOTIF = "asked_notifications"

        /** Read by the service: no "done" notification when the user is looking at the screen. */
        @Volatile
        var visible = false
    }
}
