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
import mint.app.core.model.PlaylistEntry
import mint.app.core.model.PlaylistResult
import mint.app.core.prefs.DownloadPreferences
import mint.app.core.prefs.YtResolverMode
import mint.app.core.util.Logger
import mint.app.resolution.Resolver
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object YtDlpResolver : Resolver {

    private const val TAG = "YtDlpResolver"

    private val SOUNDCLOUD_LISTING_SEGMENTS = setOf("sets", "tracks", "likes", "reposts", "albums", "spotlight")

    private var initialized = false
    private var appContext: Context? = null

    override fun initialize(context: Context) {
        if (initialized) return
        try {
            appContext = context.applicationContext
            YoutubeDL.getInstance().init(appContext!!)
            FFmpeg.getInstance().init(appContext!!)
            initialized = true
            Logger.d(TAG, "initialize: yt-dlp ready")
            maybeUpdateInBackground()
        } catch (e: Exception) {
            Logger.w(TAG, "initialize: failed", e)
        }
    }

    private fun maybeUpdateInBackground() {
        Thread {
            try {
                val ctx = appContext ?: return@Thread
                val prefs = ctx.getSharedPreferences("mint_youtubedl", Context.MODE_PRIVATE)
                val last = prefs.getLong("last_update", 0L)
                val weekMs = 7L * 24 * 60 * 60 * 1000
                if (System.currentTimeMillis() - last >= weekMs) {
                    Logger.d(TAG, "maybeUpdateInBackground: updating yt-dlp")
                    YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
                    prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
                }
            } catch (e: Exception) {
                Logger.w(TAG, "maybeUpdateInBackground: update failed", e)
            }
        }.start()
    }

    override fun supports(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return when {
            host == "youtu.be" -> true
            host == "youtube-nocookie.com" -> true
            host.endsWith("youtube.com") -> true
            host.endsWith("soundcloud.com") -> true
            else -> false
        }
    }

    override suspend fun resolve(url: String): MediaItem {
        val context = appContext
        if (context != null && DownloadPreferences.ytResolverMode(context) == YtResolverMode.ONLINE) {
            Logger.d(TAG, "resolve: online mode")
            return YtOnlineResolver.resolve(url)
        }
        return resolveLocal(url)
    }

    suspend fun resolveLocal(url: String): MediaItem = withContext(Dispatchers.IO) {
        try {
            Logger.d(TAG, "resolve: url=$url")
            val info = YoutubeDL.getInstance().getInfo(url)
            val formats = info.formats ?: emptyList()
            Logger.d(TAG, "resolve: title=${info.title} formats=${formats.size} duration=${info.duration}")

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

            val hasImages = imageFormats.isNotEmpty() || gifFormats.isNotEmpty()
            val isMusicOnly = videoFormats.isEmpty() && !hasImages
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
                .filter { it.ext?.lowercase() == "m4a" }
                .ifEmpty { audioFormats }
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
                url.contains("soundcloud.com") -> "soundcloud"
                url.contains("instagram.com") -> "instagram"
                url.contains("tiktok.com") -> "tiktok"
                else -> "other"
            }

            val finalVideoOptions = if (platform == "youtube" || platform == "tiktok") {
                videoOptions
            } else {
                listOfNotNull(videoOptions.firstOrNull { it.hasAudio } ?: videoOptions.firstOrNull())
            }

            Logger.d(
                TAG,
                "resolve: done platform=$platform video=${finalVideoOptions.size} audio=${audioOptions.size} image=${imageOptions.size}",
            )
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
            Logger.w(TAG, "resolve: yt-dlp error: ${e.message}", e)
            throw Exception("yt-dlp error: ${e.message}", e)
        } catch (e: InterruptedException) {
            Logger.w(TAG, "resolve: request interrupted", e)
            throw Exception("Request cancelled", e)
        } catch (e: Exception) {
            Logger.w(TAG, "resolve: unexpected error", e)
            throw e
        }
    }

    private fun estimateSize(format: VideoFormat, durationSeconds: Long): Long {
        when {
            format.fileSize > 0 -> return format.fileSize
            format.fileSizeApproximate > 0 -> return format.fileSizeApproximate
        }
        val tbr = if (format.tbr > 0) format.tbr else format.abr
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
        val bitrate = if (format.tbr > 0) format.tbr else format.abr
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

    fun isPlaylistUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host.endsWith("soundcloud.com")) {
            val segments = uri.path?.trim('/')?.split("/")?.filter { it.isNotBlank() }.orEmpty()
            return segments.size >= 2 && segments[1] in SOUNDCLOUD_LISTING_SEGMENTS
        }
        val isYouTubeHost = host == "youtu.be" || host == "youtube-nocookie.com" || host.endsWith("youtube.com")
        if (!isYouTubeHost) return false
        val path = uri.path?.trim('/') ?: ""
        val params = (uri.query ?: "")
            .split("&")
            .map { it.substringBefore('=') }
            .filter { it.isNotBlank() }
        val hasList = params.contains("list")
        val hasVideoId = when {
            host == "youtu.be" -> path.isNotBlank()
            params.contains("v") -> true
            path.startsWith("shorts/") -> true
            path.startsWith("embed/") -> true
            path.startsWith("live/") -> true
            path.startsWith("v/") -> true
            else -> false
        }
        return path.startsWith("playlist") || (hasList && !hasVideoId)
    }

    suspend fun resolvePlaylist(url: String, offset: Int, limit: Int): PlaylistResult = withContext(Dispatchers.IO) {
        if (!isPlaylistUrl(url)) throw Exception("Not a YouTube playlist link")
        Logger.d(TAG, "resolvePlaylist: url=$url offset=$offset limit=$limit")
        val request = YoutubeDLRequest(url)
        request.addOption("--flat-playlist")
        request.addOption("--dump-single-json")
        request.addOption("--playlist-start", offset + 1)
        request.addOption("--playlist-end", offset + limit)
        request.addOption("--no-warnings")
        val response = try {
            YoutubeDL.getInstance().execute(request, null, null)
        } catch (e: YoutubeDLException) {
            Logger.w(TAG, "resolvePlaylist: yt-dlp error: ${e.message}", e)
            throw Exception("Could not read this playlist. It may be private, empty or unavailable.", e)
        } catch (e: InterruptedException) {
            throw Exception("Request cancelled", e)
        }
        val json = runCatching { JSONObject(response.out) }.getOrNull()
            ?: throw Exception("Could not read this playlist. It may be private, empty or unavailable.")
        val array = json.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rawUrl = obj.optString("url").takeIf { it.isNotBlank() }
                ?: "https://www.youtube.com/watch?v=$id"
            PlaylistEntry(
                id = id,
                title = obj.optString("title").takeIf { it.isNotBlank() } ?: id,
                url = sanitizeVideoUrl(rawUrl, id),
                durationText = entryDuration(obj.optLong("duration", 0L)),
                thumbnailUrl = bestThumbnail(obj),
                uploader = obj.optString("uploader").takeIf { it.isNotBlank() }
                    ?: obj.optString("channel").takeIf { it.isNotBlank() }
                    ?: "",
            )
        }
        val reported = json.optInt("playlist_count", -1)
        val total = if (reported > 0) reported else entries.size
        Logger.d(TAG, "resolvePlaylist: done entries=${entries.size} total=$total")
        PlaylistResult(
            originalUrl = url,
            title = json.optString("title").takeIf { it.isNotBlank() }
                ?: json.optString("id").takeIf { it.isNotBlank() }
                ?: "Playlist",
            uploader = json.optString("uploader").takeIf { it.isNotBlank() }
                ?: json.optString("channel").takeIf { it.isNotBlank() }
                ?: "",
            entries = entries,
            totalCount = total,
            hasMore = entries.size >= limit && (reported <= 0 || offset + entries.size < reported),
        )
    }

    private fun sanitizeVideoUrl(raw: String, id: String): String {
        val host = runCatching { URI(raw).host?.lowercase() }.getOrNull()
        val isYouTube = host == "youtu.be" || host == "youtube-nocookie.com" || host?.endsWith("youtube.com") == true
        if (!isYouTube) return raw
        return "https://www.youtube.com/watch?v=$id"
    }

    private fun bestThumbnail(obj: JSONObject): String? {
        obj.optString("thumbnail").takeIf { it.isNotBlank() }?.let { return it }
        val array = obj.optJSONArray("thumbnails") ?: return null
        return (0 until array.length())
            .mapNotNull { array.optJSONObject(it)?.optString("url")?.takeIf { u -> u.isNotBlank() } }
            .lastOrNull()
    }

    private fun entryDuration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}