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

        val durationSeconds = extractor.length

        val videoOptions = (videoStreams + videoOnlyStreams)
            .sortedWith(
                compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> { it.height }
                    .thenByDescending { it.isVideoOnly }
                    .thenBy { formatRank(it.format) },
            )
            .distinctBy { it.height }
            .map { stream ->
                val format = stream.formatLabel()
                StreamOption(
                    label = "${stream.resolution ?: "unknown"} · $format",
                    format = format,
                    url = stream.url ?: "",
                    estimatedSizeBytes = estimateSize(stream.bitrate, durationSeconds),
                )
            }

        val audioOptions = audioStreams
            .map { stream ->
                val format = stream.formatLabel()
                val bitrate = if (stream.averageBitrate > 0) stream.averageBitrate else stream.bitrate
                StreamOption(
                    label = "${stream.bitrateLabel()} · $format",
                    format = format,
                    url = stream.url ?: "",
                    estimatedSizeBytes = estimateSize(bitrate, durationSeconds),
                )
            }
            .distinctBy { it.label }
            .sortedWith(compareByDescending<StreamOption> { it.bitrateKbps() })
            .take(4)

        StreamInfo(
            title = extractor.name,
            uploader = extractor.uploaderName,
            thumbnailUrl = extractor.thumbnails.firstOrNull()?.url,
            durationText = formatDuration(durationSeconds),
            isMusicOnly = isMusicOnly,
            streamType = streamType.name,
            videoOptions = videoOptions,
            audioOptions = audioOptions,
        )
    }

    private fun estimateSize(bitrateBps: Int, durationSeconds: Long): Long {
        if (bitrateBps <= 0 || durationSeconds <= 0) return 0
        return (bitrateBps.toLong() * durationSeconds) / 8
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

    private fun formatRank(format: org.schabi.newpipe.extractor.MediaFormat?): Int {
        val name = format?.name ?: return 99
        return when (name) {
            "MPEG_4" -> 0
            "v3GPP" -> 1
            "WEBM" -> 2
            else -> 3
        }
    }

    private fun org.schabi.newpipe.extractor.stream.VideoStream.formatLabel(): String =
        when (format?.name) {
            "MPEG_4" -> "mp4"
            "v3GPP" -> "3gp"
            "WEBM" -> "webm"
            else -> format?.suffix ?: "unknown"
        }

    private fun org.schabi.newpipe.extractor.stream.AudioStream.formatLabel(): String =
        when (format?.name) {
            "M4A" -> "m4a"
            "MP3" -> "mp3"
            "OPUS" -> "opus"
            "WEBMA", "WEBMA_OPUS" -> "webm"
            "FLAC" -> "flac"
            else -> format?.suffix ?: "unknown"
        }

    private fun org.schabi.newpipe.extractor.stream.AudioStream.bitrateLabel(): String {
        val itagBitrate = itagItem?.bitrate ?: 0
        val streamBitrate = if (averageBitrate > 0) averageBitrate else bitrate
        val bitrate = if (itagBitrate > 0) itagBitrate else streamBitrate
        return if (bitrate > 0) "${bitrate / 1000}kbps" else "audio"
    }

    private fun StreamOption.bitrateKbps(): Int {
        val match = Regex("(\\d+)kbps").find(label)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
