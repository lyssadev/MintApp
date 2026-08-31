package mint.app.data

data class StreamInfo(
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
    val url: String,
    val estimatedSizeBytes: Long = 0,
    val throttled: Boolean = false,
    val httpHeaders: Map<String, String> = emptyMap(),
)
