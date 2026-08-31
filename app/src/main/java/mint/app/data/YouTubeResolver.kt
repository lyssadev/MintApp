package mint.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.concurrent.atomic.AtomicBoolean

object YouTubeResolver {

    private val initialized = AtomicBoolean(false)

    fun init() {
        if (initialized.compareAndSet(false, true)) {
            NewPipe.init(NewPipeDownloader())
        }
    }

    suspend fun resolve(url: String): StreamInfo = withContext(Dispatchers.IO) {
        val service = NewPipe.getServiceByUrl(url) as? YoutubeService
            ?: throw IllegalArgumentException("Only YouTube links are supported")
        val extractor = service.getStreamExtractor(url)
        extractor.fetchPage()

        val streamType = extractor.streamType
        val videoStreams = extractor.videoStreams
        val videoOnlyStreams = extractor.videoOnlyStreams
        val audioStreams = extractor.audioStreams

        val isMusicOnly = streamType == StreamType.AUDIO_STREAM ||
            streamType == StreamType.AUDIO_LIVE_STREAM ||
            streamType == StreamType.POST_LIVE_AUDIO_STREAM ||
            (videoStreams.isEmpty() && videoOnlyStreams.isEmpty())

        val videoOptions = (videoStreams + videoOnlyStreams)
            .sortedByDescending { it.height }
            .map { stream ->
                val format = stream.format?.name ?: "unknown"
                StreamOption(
                    label = "${stream.resolution ?: "unknown"} · $format",
                    format = format,
                    url = stream.url ?: "",
                )
            }

        val audioOptions = audioStreams
            .sortedByDescending { it.averageBitrate }
            .map { stream ->
                val format = stream.format?.name ?: "unknown"
                val bitrate = if (stream.averageBitrate > 0) {
                    "${stream.averageBitrate / 1000}kbps"
                } else {
                    "audio"
                }
                StreamOption(
                    label = "$bitrate · $format",
                    format = format,
                    url = stream.url ?: "",
                )
            }

        StreamInfo(
            title = extractor.name,
            uploader = extractor.uploaderName,
            thumbnailUrl = extractor.thumbnails.firstOrNull()?.url,
            durationText = formatDuration(extractor.length),
            isMusicOnly = isMusicOnly,
            streamType = streamType.name,
            videoOptions = videoOptions,
            audioOptions = audioOptions,
        )
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "Live"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%d:%02d".format(m, s)
        }
    }
}
