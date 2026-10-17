package com.tammem.videodownloader

import android.content.Context
import android.net.Uri
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Collections
import java.util.UUID

enum class Stage { WAITING_ENGINE, FETCHING_INFO, DOWNLOADING, PHOTOS, PROCESSING, SAVING }

enum class Quality(val maxHeight: Int?) { BEST(null), P720(720), P480(480) }

sealed interface DlState {
    data object Idle : DlState

    data class Running(
        val stage: Stage,
        val title: String? = null,
        val percent: Float = -1f,
        val etaSeconds: Long = -1,
        /** Part being downloaded (video then audio), or the current photo number. */
        val part: Int = 0,
        /** Number of photos in a TikTok photo post. */
        val total: Int = 0,
    ) : DlState {
        val determinate: Boolean
            get() = (stage == Stage.DOWNLOADING || stage == Stage.PHOTOS) && percent >= 0f
    }

    data class Done(
        val uris: List<Uri>,
        val fileName: String,
        val mime: String,
        val audio: Boolean,
        /** > 0 for a TikTok photo post: [uris] holds the photos, then the music if any. */
        val photos: Int = 0,
    ) : DlState

    data class Failed(
        val message: String,
        val details: String,
        /** True when logging into TikTok would let this download work. */
        val needsLogin: Boolean = false,
    ) : DlState
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
                if (gen != generation) DlState.Idle else Errors.describe(app, e, request.url)
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

    // ───────────────────────────── Flow ─────────────────────────────

    private suspend fun download(app: Context, req: Request, gen: Int): DlState {
        val engine = Engine.awaitReady()
        if (engine is EngineState.Failed) {
            return DlState.Failed(app.getString(R.string.err_engine), engine.message)
        }
        publish(gen, DlState.Running(Stage.FETCHING_INFO))

        // A TikTok short link can hide a photo post, which yt-dlp does not understand.
        var url = req.url
        if (TikTok.isShortLink(url)) {
            val full = try {
                TikTok.resolve(url)
            } catch (e: Exception) {
                null
            }
            if (full != null && TikTok.photoPost(full) != null) url = full
        }
        TikTok.photoPost(url)?.let { return photoPost(app, req, gen, it) }

        return try {
            media(app, req, gen, url)
        } catch (e: YoutubeDLException) {
            // yt-dlp followed the short link itself and landed on a photo post.
            val photo = TikTok.photoPost(e.message.orEmpty()) ?: throw e
            photoPost(app, req, gen, photo)
        }
    }

    /** A normal video (or its sound as MP3). */
    private suspend fun media(app: Context, req: Request, gen: Int, url: String): DlState =
        Engine.lock.withLock {
            val work = freshWorkDir(app)
            try {
                val request = baseRequest(app, url, work)
                if (req.audio) addAudioOptions(request) else addVideoOptions(request, req.quality, url)
                val title = runYtDlp(request, gen)
                if (gen != generation) return@withLock DlState.Idle

                val output = findOutput(work)
                    ?: return@withLock DlState.Failed(app.getString(R.string.err_no_file), "")
                publish(gen, DlState.Running(Stage.SAVING, title ?: titleFromPath(output.name), 100f, 0))
                val saved = try {
                    Saver.save(app, output)
                } catch (e: Exception) {
                    return@withLock DlState.Failed(app.getString(R.string.err_save), e.toString())
                }
                DlState.Done(listOf(saved.uri), saved.name, saved.mime, req.audio)
            } finally {
                work.deleteRecursively()
            }
        }

    /**
     * A TikTok photo post (slideshow). yt-dlp fetches the page and the music; the photos are
     * read from the page it fetched (--dump-pages) and downloaded here.
     */
    private suspend fun photoPost(app: Context, req: Request, gen: Int, photo: TikTok.PhotoPost): DlState =
        Engine.lock.withLock {
            val work = freshWorkDir(app)
            try {
                val pages = Collections.synchronizedList(mutableListOf<String>())
                val request = baseRequest(app, photo.videoUrl, work)
                addAudioOptions(request)
                request.addOption("--ignore-no-formats-error") // a slideshow may have no music
                if (!req.audio) request.addOption("--dump-pages")

                var error: Exception? = null
                val seenTitle = try {
                    runYtDlp(request, gen) { line -> if (TikTok.isPageDump(line)) pages.add(line) }
                } catch (e: YoutubeDLException) {
                    error = e
                    null
                }
                if (gen != generation) return@withLock DlState.Idle
                val music = findOutput(work)

                if (req.audio) {
                    if (error != null) throw error
                    if (music == null) return@withLock DlState.Failed(app.getString(R.string.err_no_music), "")
                    val saved = Saver.save(app, music)
                    return@withLock DlState.Done(listOf(saved.uri), saved.name, saved.mime, true)
                }

                val show = TikTok.parseSlideshow(pages)
                if (show == null) {
                    if (error != null) throw error
                    return@withLock DlState.Failed(app.getString(R.string.err_no_photos), "")
                }

                val title = shortTitle(show.title ?: seenTitle) ?: "TikTok ${photo.id}"
                val total = show.imageUrls.size
                val uris = mutableListOf<Uri>()
                var mime = "image/jpeg"
                show.imageUrls.forEachIndexed { i, imageUrl ->
                    if (gen != generation) return@withLock DlState.Idle
                    publish(gen, DlState.Running(Stage.PHOTOS, title, i * 100f / total, -1, i + 1, total))
                    val file = TikTok.downloadImage(imageUrl, work, "$title ${i + 1}")
                    val saved = Saver.save(app, file)
                    if (i == 0) mime = saved.mime
                    uris += saved.uri
                    file.delete()
                }
                if (music != null) {
                    publish(gen, DlState.Running(Stage.SAVING, title, 100f, 0, total, total))
                    uris += Saver.save(app, music).uri
                }
                DlState.Done(uris, title, mime, audio = false, photos = total)
            } finally {
                work.deleteRecursively()
            }
        }

    // ──────────────────────────── yt-dlp ────────────────────────────

    /** Runs yt-dlp and turns its output into progress. Returns the title it saw, if any. */
    private fun runYtDlp(request: YoutubeDLRequest, gen: Int, onLine: ((String) -> Unit)? = null): String? {
        var title: String? = null
        var part = 0
        val pid = UUID.randomUUID().toString()
        processId = pid
        try {
            YoutubeDL.getInstance().execute(request, pid, false) { progress, eta, line ->
                onLine?.invoke(line)
                when {
                    line.startsWith("[download] Destination:") -> {
                        part++
                        title = titleFromPath(line.substringAfter("Destination:").trim())
                        publish(gen, DlState.Running(Stage.DOWNLOADING, title, 0f, -1, part))
                    }

                    line.startsWith("[download]") && progress >= 0f -> {
                        publish(gen, DlState.Running(Stage.DOWNLOADING, title, progress, eta, part))
                    }

                    PROCESSING_TAGS.any { line.startsWith(it) } -> {
                        publish(gen, DlState.Running(Stage.PROCESSING, title, -1f, -1, part))
                    }
                }
            }
        } finally {
            processId = null
        }
        return title
    }

    private fun baseRequest(app: Context, url: String, work: File): YoutubeDLRequest {
        val r = YoutubeDLRequest(url)
        r.addOption("-o", File(work, "%(title,id).90B.%(ext)s").absolutePath)
        r.addOption("--no-playlist")
        r.addOption("--no-mtime")
        r.addOption("--newline")
        r.addOption("--concurrent-fragments", 4)
        r.addOption("--retries", 5)
        r.addOption("--fragment-retries", 5)
        r.addOption("--socket-timeout", 20)
        // Logged into TikTok: use a copy of the cookies (yt-dlp rewrites the file it is given).
        val cookies = TikTok.cookieFile(app)
        if (Links.isTikTok(url) && cookies.exists()) {
            val copy = File(work.parentFile, "cookies.txt")
            cookies.copyTo(copy, overwrite = true)
            r.addOption("--cookies", copy.absolutePath)
        }
        return r
    }

    private fun addAudioOptions(r: YoutubeDLRequest) {
        r.addOption("-f", "ba/b")
        r.addOption("-x")
        r.addOption("--audio-format", "mp3")
        r.addOption("--audio-quality", "0")
        r.addOption("--embed-metadata")
    }

    private fun addVideoOptions(r: YoutubeDLRequest, quality: Quality, url: String) {
        // H.264 + AAC plays everywhere (phones, PCs, PowerPoint). Resolution must come before
        // the audio codec, or a low-quality file that already has sound (YouTube's 360p) would win.
        val res = quality.maxHeight?.let { "res:$it" } ?: "res"
        r.addOption("-S", "vcodec:h264,$res,acodec:m4a")
        // TikTok: never pick the watermarked copy when a clean one exists.
        val format = if (Links.isTikTok(url)) "b[format_note!*=?watermark]/bv*+ba/b" else "bv*+ba/b"
        r.addOption("-f", format)
        r.addOption("--merge-output-format", "mp4")
        r.addOption("--remux-video", "mp4")
    }

    // ──────────────────────────── Helpers ────────────────────────────

    /** Ignores progress updates that arrive after the user pressed cancel. */
    private fun publish(gen: Int, state: DlState.Running) {
        if (gen == generation) _state.value = state
    }

    private fun freshWorkDir(app: Context): File =
        File(app.cacheDir, "work").apply {
            deleteRecursively()
            mkdirs()
        }

    private fun findOutput(work: File): File? =
        work.walkTopDown().filter { it.isFile && isFinalFile(it) }.maxByOrNull { it.length() }

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

    /** First line of a TikTok caption, without the hashtags, short enough for a file name. */
    private fun shortTitle(raw: String?): String? {
        val firstLine = raw?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: return null
        val text = firstLine.replace(Regex("""#\S+"""), "").replace(Regex("""\s+"""), " ").trim()
        return text.take(50).trim().ifEmpty { null }
    }
}
