package mint.app.core.model

enum class DownloadStatus { PREPARING, DOWNLOADING, PROCESSING, COMPLETED, FAILED }

data class DownloadItem(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val status: DownloadStatus,
    val progress: Int = 0,
    val speedBytesPerSec: Long = 0,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val fileName: String = "",
    val savedPath: String = "",
    val uri: String? = null,
    val mime: String = "",
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    val isActive: Boolean
        get() = status == DownloadStatus.PREPARING ||
            status == DownloadStatus.DOWNLOADING ||
            status == DownloadStatus.PROCESSING
}
