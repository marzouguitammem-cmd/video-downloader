package com.tammem.videodownloader

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the app alive (with a progress notification) while a download runs,
 * so it finishes even if the user switches to another app.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = Downloader.state.value as? DlState.Running
            ?: DlState.Running(Stage.WAITING_ENGINE)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_PROGRESS,
                Notifications.progress(this, current), type,
            )
        } catch (e: Exception) {
            // Foreground not allowed right now: the download continues without the notification.
            stopSelf()
            return START_NOT_STICKY
        }

        if (watcher?.isActive != true) {
            watcher = scope.launch {
                Downloader.state.collect { s ->
                    if (s is DlState.Running) {
                        Notifications.notify(this@DownloadService, Notifications.ID_PROGRESS,
                            Notifications.progress(this@DownloadService, s))
                        delay(500) // the system drops notifications updated too often
                    } else {
                        finish(s)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish(s: DlState) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (!MainActivity.visible) {
            Notifications.result(this, s)?.let {
                Notifications.notify(this, Notifications.ID_RESULT, it)
            }
        }
        watcher?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
