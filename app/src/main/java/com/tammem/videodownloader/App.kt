package com.tammem.videodownloader

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        // Unpacks Python + yt-dlp + ffmpeg (first launch only) and refreshes yt-dlp in the background.
        Engine.start(this)
    }

    companion object {
        /** Lives as long as the process: downloads survive leaving the screen or rotating the phone. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
