package mint.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DownloadUiState(
    val isDownloading: Boolean = false,
    val progress: Int = 0,
    val title: String = "",
    val fileName: String = "",
    val savedPath: String = "",
    val isComplete: Boolean = false,
    val error: String? = null,
    val speedBytesPerSec: Long = 0,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
)

object DownloadManager {

    private val _state = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    fun reset() {
        _state.value = DownloadUiState()
    }

    internal fun onProgress(
        percent: Int,
        title: String,
        downloadedBytes: Long = 0,
        totalBytes: Long = 0,
        speed: Long = 0,
    ) {
        _state.value = _state.value.copy(
            isDownloading = true,
            progress = percent,
            title = title,
            isComplete = false,
            error = null,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            speedBytesPerSec = speed,
        )
    }

    internal fun onComplete(fileName: String, savedPath: String) {
        _state.value = _state.value.copy(
            isDownloading = false,
            progress = 100,
            fileName = fileName,
            savedPath = savedPath,
            isComplete = true,
            error = null,
            speedBytesPerSec = 0,
        )
    }

    internal fun onError(message: String) {
        _state.value = _state.value.copy(
            isDownloading = false,
            progress = 0,
            isComplete = false,
            error = message,
        )
    }
}