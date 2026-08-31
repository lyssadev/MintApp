package mint.app.data

data class StreamInfo(
    val originalUrl: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String?,
    val durationText: String,
    val isMusicOnly: Boolean,
    val streamType: String,
    val videoOptions: List<StreamOption>,
    val audioOptions: List<StreamOption>,
)

data class StreamOption(
    val label: String,
    val format: String,
    val formatId: String,
    val url: String,
    val estimatedSizeBytes: Long = 0,
    val hasAudio: Boolean = true,
    val httpHeaders: Map<String, String> = emptyMap(),
)
