package mint.app.core.model

data class PlaylistEntry(
    val id: String,
    val title: String,
    val url: String,
    val durationText: String,
    val thumbnailUrl: String?,
    val uploader: String,
)

data class PlaylistResult(
    val originalUrl: String,
    val title: String,
    val uploader: String,
    val entries: List<PlaylistEntry>,
    val totalCount: Int,
    val hasMore: Boolean,
)
