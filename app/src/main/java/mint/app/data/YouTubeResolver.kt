package mint.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.mapper.VideoFormat

object YouTubeResolver {

    private var initialized = false
    private var updateDone = false
    private var appContext: Context? = null

    fun init(context: Context) {
        if (initialized) return
        try {
            appContext = context.applicationContext
            YoutubeDL.getInstance().init(appContext!!)
            initialized = true
        } catch (_: Exception) { }
    }

    suspend fun resolve(url: String): StreamInfo = withContext(Dispatchers.IO) {
        if (!updateDone) {
            val ctx = appContext
            if (ctx != null) {
                runCatching { YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE) }
            }
            updateDone = true
        }
        try {
            val info = YoutubeDL.getInstance().getInfo(url)
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

            val isMusicOnly = videoFormats.isEmpty()

            val videoOptions = videoFormats
                .sortedWith(
                    compareByDescending<VideoFormat> { it.height ?: 0 }
                        .thenByDescending { it.tbr }
                )
                .distinctBy { it.height?.let { it / 144 * 144 } ?: 0 }
                .map { format ->
                    val label = buildVideoLabel(format)
                    StreamOption(
                        label = label,
                        format = format.ext ?: "mp4",
                        url = format.url ?: "",
                        estimatedSizeBytes = format.fileSize
                            ?: (format.fileSizeApproximate ?: 0L),
                        throttled = false,
                        httpHeaders = format.httpHeaders ?: emptyMap(),
                    )
                }

            val audioOptions = audioFormats
                .sortedByDescending { it.tbr }
                .map { format ->
                    val label = buildAudioLabel(format)
                    StreamOption(
                        label = label,
                        format = format.ext ?: "m4a",
                        url = format.url ?: "",
                        estimatedSizeBytes = format.fileSize
                            ?: (format.fileSizeApproximate ?: 0L),
                        throttled = false,
                        httpHeaders = format.httpHeaders ?: emptyMap(),
                    )
                }
                .distinctBy { it.label }
                .take(4)

            StreamInfo(
                title = info.title ?: "Unknown",
                uploader = info.uploader ?: "Unknown",
                thumbnailUrl = info.thumbnail,
                durationText = formatDuration((info.duration ?: 0).toLong()),
                isMusicOnly = isMusicOnly,
                streamType = if (isMusicOnly) "AUDIO" else "VIDEO",
                videoOptions = videoOptions,
                audioOptions = audioOptions,
            )
        } catch (e: YoutubeDLException) {
            throw Exception("yt-dlp error: ${e.message}", e)
        } catch (e: InterruptedException) {
            throw Exception("Request cancelled", e)
        }
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