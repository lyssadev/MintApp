package mint.app.resolution.impl

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import mint.app.core.model.MediaFormat
import mint.app.core.model.MediaItem
import mint.app.core.prefs.ConnectionPreferences
import mint.app.resolution.Resolver
import java.io.File

object YtDlpResolver : Resolver {

    private var initialized = false
    private var appContext: Context? = null

    override fun initialize(context: Context) {
        if (initialized) return
        try {
            appContext = context.applicationContext
            YoutubeDL.getInstance().init(appContext!!)
            FFmpeg.getInstance().init(appContext!!)
            initialized = true
            maybeUpdateInBackground()
        } catch (_: Exception) { }
    }

    private fun maybeUpdateInBackground() {
        Thread {
            try {
                val ctx = appContext ?: return@Thread
                val prefs = ctx.getSharedPreferences("mint_youtubedl", Context.MODE_PRIVATE)
                val last = prefs.getLong("last_update", 0L)
                val weekMs = 7L * 24 * 60 * 60 * 1000
                if (System.currentTimeMillis() - last >= weekMs) {
                    YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
                    prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
                }
            } catch (_: Exception) { }
        }.start()
    }

    override fun supports(url: String): Boolean = true

    override suspend fun resolve(url: String): MediaItem = withContext(Dispatchers.IO) {
        try {
            val info = if (url.contains("instagram.com")) {
                val request = YoutubeDLRequest(url)
                writeCookiesFile()?.let { cookiesFile ->
                    request.addOption("--cookies", cookiesFile.absolutePath)
                }
                YoutubeDL.getInstance().getInfo(request)
            } else {
                YoutubeDL.getInstance().getInfo(url)
            }
            val formats = info.formats ?: emptyList()

            val videoFormats = formats.filter { f ->
                val vc = f.vcodec ?: "none"
                vc != "none" && (f.width ?: 0) > 0
            }
            val audioFormats = formats.filter { f ->
                val ac = f.acodec ?: "none"
                val vc = f.vcodec ?: "none"
                ac != "none" && vc == "none"
            }
            val imageFormats = formats.filter { f ->
                val vc = f.vcodec ?: "none"
                val ac = f.acodec ?: "none"
                vc == "none" && ac == "none" && (f.width ?: 0) > 0
            }
            val gifFormats = formats.filter { f ->
                val vc = f.vcodec ?: "none"
                val ac = f.acodec ?: "none"
                val ext = f.ext?.lowercase()
                (ext == "gif" || ext == "webp") && vc != "none" && (f.width ?: 0) > 0
            }

            val isMusicOnly = videoFormats.isEmpty()
            val durationSec = (info.duration ?: 0).toLong()

            val videoOptions = videoFormats
                .sortedWith(
                    compareByDescending<VideoFormat> { it.height ?: 0 }
                        .thenByDescending { it.tbr }
                )
                .distinctBy { it.height?.let { it / 144 * 144 } ?: 0 }
                .map { format ->
                    val label = buildVideoLabel(format)
                    MediaFormat(
                        label = label,
                        format = format.ext ?: "mp4",
                        formatId = format.formatId ?: "",
                        url = format.url ?: "",
                        estimatedSizeBytes = estimateSize(format, durationSec),
                        hasAudio = (format.acodec ?: "none") != "none",
                        httpHeaders = format.httpHeaders ?: emptyMap(),
                    )
                }

            val audioOptions = audioFormats
                .sortedByDescending { it.tbr }
                .map { format ->
                    val label = buildAudioLabel(format)
                    MediaFormat(
                        label = label,
                        format = format.ext ?: "m4a",
                        formatId = format.formatId ?: "",
                        url = format.url ?: "",
                        estimatedSizeBytes = estimateSize(format, durationSec),
                        hasAudio = true,
                        httpHeaders = format.httpHeaders ?: emptyMap(),
                    )
                }
                .distinctBy { it.label }
                .take(4)

            val imageOptions = imageFormats
                .sortedBy { it.formatId ?: "" }
                .mapIndexed { index, format ->
                    val label = "Image ${index + 1} · ${format.ext ?: "jpg"}"
                    MediaFormat(
                        label = label,
                        format = format.ext ?: "jpg",
                        formatId = format.formatId ?: "",
                        url = format.url ?: "",
                        estimatedSizeBytes = estimateSize(format, durationSec),
                        hasAudio = false,
                        httpHeaders = format.httpHeaders ?: emptyMap(),
                    )
                }

            val gifOptions = gifFormats
                .mapIndexed { index, format ->
                    MediaFormat(
                        label = "GIF ${index + 1} · ${format.ext ?: "gif"}",
                        format = format.ext ?: "gif",
                        formatId = format.formatId ?: "",
                        url = format.url ?: "",
                        estimatedSizeBytes = estimateSize(format, durationSec),
                        hasAudio = false,
                        httpHeaders = format.httpHeaders ?: emptyMap(),
                    )
                }

            val platform = when {
                url.contains("youtube.com") || url.contains("youtu.be") || url.contains("youtube-nocookie.com") -> "youtube"
                url.contains("instagram.com") -> "instagram"
                url.contains("tiktok.com") -> "tiktok"
                else -> "other"
            }

            val finalVideoOptions = if (platform == "youtube") {
                videoOptions
            } else {
                listOfNotNull(videoOptions.firstOrNull { it.hasAudio } ?: videoOptions.firstOrNull())
            }

            MediaItem(
                originalUrl = url,
                title = info.title ?: "Unknown",
                uploader = info.uploader ?: "Unknown",
                thumbnailUrl = info.thumbnail,
                durationText = formatDuration((info.duration ?: 0).toLong()),
                isMusicOnly = isMusicOnly,
                streamType = if (isMusicOnly) "AUDIO" else "VIDEO",
                platform = platform,
                videoOptions = finalVideoOptions,
                audioOptions = audioOptions,
                imageOptions = imageOptions,
                gifOptions = gifOptions,
            )
        } catch (e: YoutubeDLException) {
            throw Exception("yt-dlp error: ${e.message}", e)
        } catch (e: InterruptedException) {
            throw Exception("Request cancelled", e)
        }
    }

    private fun writeCookiesFile(): File? {
        val ctx = appContext ?: return null
        val cookies = ConnectionPreferences.instagramCookies(ctx)
        if (cookies.isEmpty()) return null
        return try {
            val file = File(ctx.cacheDir, "instagram_cookies.txt")
            val lines = mutableListOf("# Netscape HTTP Cookie File")
            cookies.forEach { (name, value) ->
                val secure = if (name in setOf("sessionid", "csrftoken", "ds_user_id")) "TRUE" else "FALSE"
                lines += listOf(
                    "#HttpOnly_.instagram.com",
                    "TRUE",
                    "/",
                    secure,
                    "0",
                    name,
                    value,
                ).joinToString("\t")
            }
            file.writeText(lines.joinToString("\n"))
            file
        } catch (_: Exception) {
            null
        }
    }

    private fun estimateSize(format: VideoFormat, durationSeconds: Long): Long {
        when {
            format.fileSize > 0 -> return format.fileSize
            format.fileSizeApproximate > 0 -> return format.fileSizeApproximate
        }
        val tbr = format.tbr
        if (tbr > 0 && durationSeconds > 0) {
            return tbr.toLong() * 1000L / 8L * durationSeconds
        }
        return 0L
    }

    private fun buildVideoLabel(format: VideoFormat): String {
        val label = format.formatNote ?: format.resolutionString()
        val ext = format.ext ?: "mp4"
        return "$label · $ext"
    }

    private fun buildAudioLabel(format: VideoFormat): String {
        val ext = format.ext ?: "m4a"
        val bitrate = format.tbr
        val rate = if (bitrate > 0) "${bitrate}kbps" else "audio"
        return "$rate · $ext"
    }

    private fun VideoFormat.resolutionString(): String {
        val w = width ?: 0
        val h = height ?: 0
        return if (h > 0) "${h}p" else "unknown"
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "Live"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}