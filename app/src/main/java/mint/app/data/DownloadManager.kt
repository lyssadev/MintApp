package mint.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DownloadPhase { PREPARING, DOWNLOADING, PROCESSING }

data class DownloadUiState(
    val isDownloading: Boolean = false,
    val phase: DownloadPhase = DownloadPhase.PREPARING,
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

    internal fun onPreparing(title: String) {
        _state.value = DownloadUiState(
            isDownloading = true,
            phase = DownloadPhase.PREPARING,
            progress = 0,
            title = title,
        )
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
            phase = DownloadPhase.DOWNLOADING,
            progress = percent,
            title = title,
            isComplete = false,
            error = null,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            speedBytesPerSec = speed,
        )
    }

    internal fun onProcessing(title: String) {
        _state.value = _state.value.copy(
            isDownloading = true,
            phase = DownloadPhase.PROCESSING,
            progress = 100,
            title = title,
            isComplete = false,
            error = null,
            speedBytesPerSec = 0,
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