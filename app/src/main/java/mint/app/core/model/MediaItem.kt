package mint.app.core.model

data class MediaItem(
    val originalUrl: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String?,
    val durationText: String,
    val isMusicOnly: Boolean,
    val streamType: String,
    val platform: String = "other",
    val videoOptions: List<MediaFormat>,
    val audioOptions: List<MediaFormat>,
    val imageOptions: List<MediaFormat> = emptyList(),
)

data class MediaFormat(
    val label: String,
    val format: String,
    val formatId: String,
    val url: String,
    val estimatedSizeBytes: Long = 0,
    val hasAudio: Boolean = true,
    val httpHeaders: Map<String, String> = emptyMap(),
)
