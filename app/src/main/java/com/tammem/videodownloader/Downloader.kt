package com.tammem.videodownloader

import android.content.Context
import android.net.Uri
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

enum class Stage { WAITING_ENGINE, FETCHING_INFO, DOWNLOADING, PROCESSING, SAVING }

enum class Quality(val maxHeight: Int?) { BEST(null), P720(720), P480(480) }

sealed interface DlState {
    data object Idle : DlState

    data class Running(
        val stage: Stage,
        val title: String? = null,
        val percent: Float = -1f,
        val etaSeconds: Long = -1,
        val part: Int = 0,
    ) : DlState

    data class Done(
        val uri: Uri,
        val fileName: String,
        val mime: String,
        val audio: Boolean,
    ) : DlState

    data class Failed(val message: String, val details: String) : DlState
}

object Downloader {

    data class Request(val url: String, val audio: Boolean, val quality: Quality)

    private val _state = MutableStateFlow<DlState>(DlState.Idle)
    val state: StateFlow<DlState> = _state.asStateFlow()

    var lastRequest: Request? = null
        private set

    private var job: Job? = null

    @Volatile
    private var processId: String? = null

    /** Bumped on every start and cancel; a job whose number is stale may no longer touch the state. */
    @Volatile
    private var generation = 0

    val isRunning: Boolean get() = _state.value is DlState.Running

    /** Returns false if a download is already running. */
    fun start(context: Context, request: Request): Boolean {
        if (isRunning) return false
        val app = context.applicationContext
        lastRequest = request
        val gen = ++generation
        _state.value = DlState.Running(Stage.WAITING_ENGINE)
        job = App.scope.launch {
            val result: DlState = try {
                download(app, request, gen)
            } catch (e: YoutubeDL.CanceledException) {
                DlState.Idle
            } catch (e: Throwable) {
                if (gen != generation) DlState.Idle else Errors.describe(app, e)
            }
            if (gen == generation) _state.value = result
        }
        return true
    }

    fun cancel() {
        if (!isRunning) return
        generation++
        processId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
        // Also stops a download that is still waiting for the engine.
        job?.cancel()
        _state.value = DlState.Idle
    }

    fun reset() {
        if (!isRunning) _state.value = DlState.Idle
    }

    private suspend fun download(app: Context, req: Request, gen: Int): DlState {
        val engine = Engine.awaitReady()
        if (engine is EngineState.Failed) {
            return DlState.Failed(app.getString(R.string.err_engine), engine.message)
        }
        return Engine.lock.withLock {
            val work = File(app.cacheDir, "work").apply {
                deleteRecursively()
                mkdirs()
            }
            try {
                publish(gen, DlState.Running(Stage.FETCHING_INFO))
                var title: String? = null
                var part = 0
                val pid = UUID.randomUUID().toString()
                processId = pid

                YoutubeDL.getInstance().execute(buildRequest(req, work), pid, false) { progress, eta, line ->
                    val text = line.trim()
                    when {
                        text.startsWith("[download] Destination:") -> {
                            part++
                            title = titleFromPath(text.substringAfter("Destination:").trim())
                            publish(gen, DlState.Running(Stage.DOWNLOADING, title, 0f, -1, part))
                        }

                        text.startsWith("[download]") && progress >= 0f -> {
                            publish(gen, DlState.Running(Stage.DOWNLOADING, title, progress, eta, part))
                        }

                        PROCESSING_TAGS.any { text.startsWith(it) } -> {
                            publish(gen, DlState.Running(Stage.PROCESSING, title, -1f, -1, part))
                        }
                    }
                }
                processId = null
                if (gen != generation) return@withLock DlState.Idle

                val output = work.walkTopDown()
                    .filter { it.isFile && isFinalFile(it) }
                    .maxByOrNull { it.length() }
                    ?: return@withLock DlState.Failed(app.getString(R.string.err_no_file), "")

                publish(gen, DlState.Running(Stage.SAVING, title ?: titleFromPath(output.name), 100f, 0, part))
                val saved = try {
                    Saver.save(app, output)
                } catch (e: Exception) {
                    return@withLock DlState.Failed(app.getString(R.string.err_save), e.toString())
                }
                DlState.Done(saved.uri, saved.name, saved.mime, req.audio)
            } finally {
                processId = null
                work.deleteRecursively()
            }
        }
    }

    /** Ignores progress updates that arrive after the user pressed cancel. */
    private fun publish(gen: Int, state: DlState.Running) {
        if (gen == generation) _state.value = state
    }

    private fun buildRequest(req: Request, work: File): YoutubeDLRequest {
        val r = YoutubeDLRequest(req.url)
        r.addOption("-o", File(work, "%(title,id).90B.%(ext)s").absolutePath)
        r.addOption("--no-playlist")
        r.addOption("--no-mtime")
        r.addOption("--newline")
        r.addOption("--concurrent-fragments", 4)
        r.addOption("--retries", 5)
        r.addOption("--fragment-retries", 5)
        r.addOption("--socket-timeout", 20)

        if (req.audio) {
            r.addOption("-f", "ba/b")
            r.addOption("-x")
            r.addOption("--audio-format", "mp3")
            r.addOption("--audio-quality", "0")
            r.addOption("--embed-metadata")
        } else {
            // H.264 + AAC plays everywhere (phones, PCs, PowerPoint).
            // Resolution must come before the audio codec, or a low-quality file
            // that already contains sound (YouTube's 360p) would win.
            val res = req.quality.maxHeight?.let { "res:$it" } ?: "res"
            val sort = "vcodec:h264,$res,acodec:m4a"
            r.addOption("-S", sort)
            // TikTok: never pick the watermarked copy when a clean one exists.
            val format = if (Links.isTikTok(req.url)) {
                "b[format_note!*=?watermark]/bv*+ba/b"
            } else {
                "bv*+ba/b"
            }
            r.addOption("-f", format)
            r.addOption("--merge-output-format", "mp4")
            r.addOption("--remux-video", "mp4")
        }
        return r
    }

    private val PROCESSING_TAGS = listOf(
        "[Merger]", "[ExtractAudio]", "[VideoRemuxer]", "[VideoConvertor]",
        "[Metadata]", "[Fixup", "[ffmpeg]",
    )

    private val TEMP_EXTENSIONS = setOf(
        "part", "ytdl", "temp", "tmp", "json", "jpg", "jpeg", "png", "webp", "vtt", "srt",
    )

    private fun isFinalFile(f: File): Boolean =
        f.extension.lowercase() !in TEMP_EXTENSIONS && !f.name.contains(".part-Frag")

    private fun titleFromPath(path: String): String {
        val name = path.substringAfterLast('/')
        // "Title.f137.mp4" → "Title"
        return name.substringBeforeLast('.').replace(Regex("""\.f\d+[\w-]*$"""), "")
    }
}
