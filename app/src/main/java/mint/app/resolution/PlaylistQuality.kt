package mint.app.resolution

data class PlaylistQualityOption(
    val label: String,
    val selector: String,
    val format: String,
    val hasAudio: Boolean,
)

object PlaylistQuality {

    const val PAGE_SIZE = 100

    val options: List<PlaylistQualityOption> = listOf(
        PlaylistQualityOption("Best", "", "mp4", true),
        PlaylistQualityOption("1080p", "bestvideo[height<=1080]+bestaudio/best[height<=1080]", "mp4", true),
        PlaylistQualityOption("720p", "bestvideo[height<=720]+bestaudio/best[height<=720]", "mp4", true),
        PlaylistQualityOption("480p", "bestvideo[height<=480]+bestaudio/best[height<=480]", "mp4", true),
        PlaylistQualityOption("360p", "bestvideo[height<=360]+bestaudio/best[height<=360]", "mp4", true),
        PlaylistQualityOption("Audio · m4a", "bestaudio[ext=m4a]/bestaudio", "m4a", true),
    )
}
