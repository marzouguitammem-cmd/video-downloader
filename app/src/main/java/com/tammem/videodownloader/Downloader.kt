package com.tammem.videodownloader

import android.content.Context
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
        /** Part being downloaded (video, audio, or post item), or the current photo number. */
        val part: Int = 0,
        /** Number of photos in a TikTok photo post. */
        val total: Int = 0,
    ) : DlState {
        val determinate: Boolean
            get() = (stage == Stage.DOWNLOADING || stage == Stage.PHOTOS) && percent >= 0f
    }

    data class Done(
        /** Everything that was saved: one video, or the photos/videos/music of a post. */
        val files: List<Saver.Saved>,
        val title: String,
        val audio: Boolean,
    ) : DlState {
        val first: Saver.Saved get() = files.first()
        fun count(prefix: String) = files.count { it.mime.startsWith(prefix) }
    }

    data class Failed(
        val message: String,
        val details: String,
        /** Set when logging into this site would let the download work. */
        val loginSite: Site? = null,
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
                if (gen != generation) DlState.Idle else Errors.describe(app, e, request)
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
        // Google Drive / Docs / Dropbox, and links to documents or pictures: saved as they are,
        // without yt-dlp.
        DirectFile.cloudLink(req.url)?.let { link ->
            if (link.isFolder) return DlState.Failed(app.getString(R.string.err_drive_folder), "")
            return directFile(app, gen, link.url, link.kind)
        }
        if (DirectFile.looksLikeFile(req.url)) return directFile(app, gen, req.url, DirectFile.Kind.PLAIN)

        val engine = Engine.awaitReady()
        if (engine is EngineState.Failed) {
            return DlState.Failed(app.getString(R.string.err_engine), engine.message)
        }
        publish(gen, DlState.Running(Stage.FETCHING_INFO))

        var url = req.url
        // Facebook / Instagram "share" links: find the real post first (with the login, if any).
        if (Links.isShareLink(url)) {
            val cookies = Site.forUrl(url)?.let { Accounts.cookieHeader(app, it) }
            val full = try {
                Links.resolve(url, cookies)
            } catch (e: Exception) {
                null
            }
            if (full != null) url = full
        }
        // A TikTok short link can hide a photo post, which yt-dlp does not understand.
        if (TikTok.isShortLink(url)) {
            val full = try {
                Links.resolve(url)
            } catch (e: Exception) {
                null
            }
            if (full != null && TikTok.photoPost(full) != null) url = full
        }
        TikTok.photoPost(url)?.let { return tiktokPhotoPost(app, req, gen, it) }

        return try {
            media(app, req, gen, url)
        } catch (e: YoutubeDLException) {
            // yt-dlp followed the short link itself and landed on a photo post.
            TikTok.photoPost(e.message.orEmpty())?.let { return tiktokPhotoPost(app, req, gen, it) }
            // Any other site: no video found there, but the link may still be a file to save.
            val unsupported = e.message.orEmpty().contains("Unsupported URL", ignoreCase = true)
            if (unsupported && Site.forUrl(url) == null && !Links.isYouTube(url)) {
                return directFile(app, gen, url, DirectFile.Kind.PLAIN, afterVideoSearch = true)
            }
            throw e
        }
    }

    /** Saves the file behind [url] as it is (PDF, Word, pictures, Drive files…). */
    private fun directFile(
        app: Context,
        gen: Int,
        url: String,
        kind: DirectFile.Kind,
        afterVideoSearch: Boolean = false,
    ): DlState {
        publish(gen, DlState.Running(Stage.FETCHING_INFO))
        val work = File(app.cacheDir, "direct").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            var title: String? = null
            val result = try {
                DirectFile.fetch(url, work) { percent, eta ->
                    if (title == null) title = work.listFiles()?.firstOrNull()?.name
                    publish(gen, DlState.Running(Stage.DOWNLOADING, title, percent, eta, 1))
                    gen == generation
                }
            } catch (e: DirectFile.Stopped) {
                return DlState.Idle
            }
            if (gen != generation) return DlState.Idle
            return when (result) {
                is DirectFile.Result.Ok -> {
                    publish(gen, DlState.Running(Stage.SAVING, result.file.name, 100f, 0, 1))
                    val saved = try {
                        Saver.save(app, result.file, result.mime)
                    } catch (e: Exception) {
                        return DlState.Failed(app.getString(R.string.err_save), e.toString())
                    }
                    DlState.Done(listOf(saved), saved.name, audio = saved.mime.startsWith("audio/"))
                }
                DirectFile.Result.Page -> DlState.Failed(
                    app.getString(
                        when {
                            kind != DirectFile.Kind.PLAIN -> R.string.err_drive_private
                            afterVideoSearch -> R.string.err_nothing_found
                            else -> R.string.err_page_not_file
                        },
                    ),
                    url,
                )
                is DirectFile.Result.HttpError -> DlState.Failed(
                    app.getString(
                        when {
                            kind != DirectFile.Kind.PLAIN && result.code in listOf(401, 403, 404) ->
                                R.string.err_drive_private
                            result.code == 404 || result.code == 410 -> R.string.err_file_missing
                            result.code == 401 || result.code == 403 -> R.string.err_file_forbidden
                            else -> R.string.err_generic
                        },
                    ),
                    "HTTP ${result.code}: $url",
                )
            }
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * A video (or its sound as MP3). Also handles posts with several items: Instagram carousels
     * (photos and videos) and Facebook posts with several videos.
     */
    private suspend fun media(app: Context, req: Request, gen: Int, url: String): DlState =
        Engine.lock.withLock {
            val work = freshWorkDir(app)
            try {
                val instagram = Links.isInstagram(url)
                val request = baseRequest(app, url, work)
                if (req.audio) addAudioOptions(request) else addVideoOptions(request, req.quality, url)
                if (instagram) {
                    // A photo has no "video formats": keep going, and save the photo itself
                    // (yt-dlp's thumbnail of that item is the full-size picture).
                    request.addOption("--ignore-no-formats-error")
                    request.addOption("--ignore-errors")
                    if (!req.audio) request.addOption("--write-thumbnail")
                }

                val title = try {
                    runYtDlp(request, gen)
                } catch (e: YoutubeDLException) {
                    // Photos end with "No video formats found!": that is expected, anything else is not.
                    if (!instagram || !onlyPhotoErrors(e.message.orEmpty())) throw e
                    null
                }
                if (gen != generation) return@withLock DlState.Idle

                val outputs = collectOutputs(work, keepPhotos = instagram && !req.audio)
                if (outputs.isEmpty()) {
                    val msg = if (instagram && req.audio) R.string.err_photos_only_audio else R.string.err_no_file
                    return@withLock DlState.Failed(app.getString(msg), "")
                }

                val shownTitle = title ?: titleFromPath(outputs.first().name)
                val saved = mutableListOf<Saver.Saved>()
                outputs.forEachIndexed { i, file ->
                    if (gen != generation) return@withLock DlState.Idle
                    publish(gen, DlState.Running(Stage.SAVING, shownTitle, 100f, 0, i + 1))
                    saved += try {
                        Saver.save(app, file)
                    } catch (e: Exception) {
                        return@withLock DlState.Failed(app.getString(R.string.err_save), e.toString())
                    }
                }
                DlState.Done(saved, if (saved.size == 1) saved[0].name else shownTitle, req.audio)
            } finally {
                work.deleteRecursively()
            }
        }

    /**
     * A TikTok photo post (slideshow). yt-dlp fetches the page and the music; the photos are
     * read from the page it fetched (--dump-pages) and downloaded here.
     */
    private suspend fun tiktokPhotoPost(app: Context, req: Request, gen: Int, photo: TikTok.PhotoPost): DlState =
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
                val music = collectOutputs(work, keepPhotos = false).maxByOrNull { it.length() }

                if (req.audio) {
                    if (error != null) throw error
                    if (music == null) return@withLock DlState.Failed(app.getString(R.string.err_no_music), "")
                    val saved = Saver.save(app, music)
                    return@withLock DlState.Done(listOf(saved), saved.name, true)
                }

                val show = TikTok.parseSlideshow(pages)
                if (show == null) {
                    if (error != null) throw error
                    return@withLock DlState.Failed(app.getString(R.string.err_no_photos), "")
                }

                val title = shortTitle(show.title ?: seenTitle) ?: "TikTok ${photo.id}"
                val total = show.imageUrls.size
                val saved = mutableListOf<Saver.Saved>()
                show.imageUrls.forEachIndexed { i, imageUrl ->
                    if (gen != generation) return@withLock DlState.Idle
                    publish(gen, DlState.Running(Stage.PHOTOS, title, i * 100f / total, -1, i + 1, total))
                    val file = TikTok.downloadImage(imageUrl, work, "$title ${i + 1}")
                    saved += Saver.save(app, file)
                    file.delete()
                }
                if (music != null) {
                    publish(gen, DlState.Running(Stage.SAVING, title, 100f, 0, total, total))
                    saved += Saver.save(app, music)
                }
                DlState.Done(saved, title, audio = false)
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
        // Items of one post get " 1", " 2"… so they do not overwrite each other.
        val name = if (Links.isInstagram(url)) "%(channel,uploader_id,title,id).80B" else "%(title,id).80B"
        r.addOption("-o", File(work, "$name%(playlist_index& {}|)s.%(ext)s").absolutePath)
        r.addOption("--no-playlist")
        r.addOption("--no-mtime")
        r.addOption("--newline")
        r.addOption("--concurrent-fragments", 4)
        r.addOption("--retries", 5)
        r.addOption("--fragment-retries", 5)
        r.addOption("--socket-timeout", 20)
        // Logged in: use a copy of the cookies (yt-dlp rewrites the file it is given).
        val site = Site.forUrl(url)
        if (site != null && Accounts.isLoggedIn(app, site)) {
            val copy = File(work.parentFile, "cookies.txt")
            Accounts.cookieFile(app, site).copyTo(copy, overwrite = true)
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

    /** True when every yt-dlp error is the "this item is a photo" one. */
    private fun onlyPhotoErrors(stderr: String): Boolean {
        val errors = stderr.lines().filter { it.contains("ERROR:") }
        return errors.isNotEmpty() && errors.all { it.contains("No video formats found") }
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

    private val PROCESSING_TAGS = listOf(
        "[Merger]", "[ExtractAudio]", "[VideoRemuxer]", "[VideoConvertor]",
        "[Metadata]", "[Fixup", "[ffmpeg]",
    )

    private val MEDIA_EXTENSIONS = setOf(
        "mp4", "m4v", "webm", "mkv", "mov", "mp3", "m4a", "aac", "opus", "ogg", "wav", "flac",
    )
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic")

    /**
     * The finished files, in post order. A picture is kept only when it is a post item of its
     * own; a picture next to a video of the same name is just that video's cover.
     */
    private fun collectOutputs(work: File, keepPhotos: Boolean): List<File> {
        val files = work.walkTopDown().filter { it.isFile && !isTemporary(it) }.toList()
        val media = files.filter { it.extension.lowercase() in MEDIA_EXTENSIONS }
        val mediaNames = media.map { it.nameWithoutExtension }.toSet()
        val photos = if (keepPhotos) {
            files.filter { it.extension.lowercase() in IMAGE_EXTENSIONS && it.nameWithoutExtension !in mediaNames }
        } else {
            emptyList()
        }
        return (media + photos).sortedWith(compareBy(NATURAL) { it.nameWithoutExtension })
    }

    private val FORMAT_PART = Regex("""\.f\d+(-[\w-]+)?$""")

    /** Unfinished downloads, and "Title.f137.mp4" streams that were waiting to be merged. */
    private fun isTemporary(f: File): Boolean {
        val n = f.name
        return n.endsWith(".part") || n.contains(".part-Frag") || n.endsWith(".ytdl") ||
            n.endsWith(".temp") || n.endsWith(".tmp") || FORMAT_PART.containsMatchIn(f.nameWithoutExtension)
    }

    /** "name 2" before "name 10". */
    private val NATURAL = Comparator<String> { a, b ->
        val na = a.substringAfterLast(' ').toIntOrNull()
        val nb = b.substringAfterLast(' ').toIntOrNull()
        if (na != null && nb != null && a.substringBeforeLast(' ') == b.substringBeforeLast(' ')) {
            na.compareTo(nb)
        } else {
            a.compareTo(b)
        }
    }

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
