package com.tammem.videodownloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface EngineState {
    data object Starting : EngineState
    data object Updating : EngineState
    data class Ready(val version: String?) : EngineState
    data class Failed(val message: String) : EngineState
}

/**
 * Wraps yt-dlp. YouTube and TikTok change often, so yt-dlp is refreshed from its
 * official GitHub releases every [UPDATE_EVERY_MS]; the copy bundled in the APK is only a fallback.
 */
object Engine {
    private const val PREFS = "engine"
    private const val KEY_LAST_UPDATE = "last_update"
    private const val UPDATE_EVERY_MS = 12L * 60 * 60 * 1000

    private val _state = MutableStateFlow<EngineState>(EngineState.Starting)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Held while yt-dlp runs or while its file is replaced, so the two never overlap. */
    val lock = Mutex()

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        App.scope.launch {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
            } catch (e: Throwable) {
                _state.value = EngineState.Failed(e.message ?: e.toString())
                return@launch
            }
            val last = prefs(app).getLong(KEY_LAST_UPDATE, 0L)
            if (System.currentTimeMillis() - last > UPDATE_EVERY_MS) {
                runUpdate(app)
            }
            _state.value = EngineState.Ready(version(app))
        }
    }

    /** Suspends until the engine is usable (or has definitely failed). */
    suspend fun awaitReady(): EngineState =
        state.first { it is EngineState.Ready || it is EngineState.Failed }

    /** true = updated, false = already current, null = failed. */
    suspend fun updateNow(context: Context): Boolean? {
        val app = context.applicationContext
        if (awaitReady() is EngineState.Failed) return null
        val result = runUpdate(app)
        _state.value = EngineState.Ready(version(app))
        return result
    }

    private suspend fun runUpdate(app: Context): Boolean? = lock.withLock {
        _state.value = EngineState.Updating
        try {
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            prefs(app).edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
            status == YoutubeDL.UpdateStatus.DONE
        } catch (e: Throwable) {
            null
        }
    }

    fun version(context: Context): String? = try {
        YoutubeDL.getInstance().version(context)
    } catch (e: Throwable) {
        null
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
