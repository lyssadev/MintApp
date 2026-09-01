package mint.app.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

object DownloadManager {

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun load(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences("mint_downloads", Context.MODE_PRIVATE)
        }
        val persisted = parse(prefs?.getString(KEY, null))
        val current = _items.value
        // merge: keep in-memory active items, add persisted ones not already present
        _items.value = current + persisted.filter { p -> current.none { it.id == p.id } }
        refreshActiveCount()
    }

    fun addActive(id: String, title: String, thumbnailUrl: String?) {
        _items.value = listOf(
            DownloadItem(
                id = id,
                title = title,
                thumbnailUrl = thumbnailUrl,
                status = DownloadStatus.PREPARING,
            ),
        ) + _items.value.filterNot { it.id == id }
        refreshActiveCount()
    }

    fun updateProgress(id: String, percent: Int, downloadedBytes: Long, totalBytes: Long, speed: Long) {
        _items.value = _items.value.map {
            if (it.id == id) {
                it.copy(
                    status = DownloadStatus.DOWNLOADING,
                    progress = percent,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    speedBytesPerSec = speed,
                    error = null,
                )
            } else {
                it
            }
        }
    }

    fun updatePhase(id: String, status: DownloadStatus) {
        _items.value = _items.value.map {
            if (it.id == id) {
                it.copy(
                    status = status,
                    progress = if (status == DownloadStatus.PROCESSING) 100 else it.progress,
                )
            } else {
                it
            }
        }
        refreshActiveCount()
    }

    fun complete(id: String, fileName: String, savedPath: String, uri: String?, mime: String, sizeBytes: Long) {
        _items.value = _items.value.map {
            if (it.id == id) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                    fileName = fileName,
                    savedPath = savedPath,
                    uri = uri,
                    mime = mime,
                    downloadedBytes = sizeBytes,
                    totalBytes = sizeBytes,
                    speedBytesPerSec = 0,
                    error = null,
                )
            } else {
                it
            }
        }
        persist()
        refreshActiveCount()
    }

    fun fail(id: String, error: String) {
        _items.value = _items.value.map {
            if (it.id == id) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    progress = 0,
                    speedBytesPerSec = 0,
                    error = error,
                )
            } else {
                it
            }
        }
        persist()
        refreshActiveCount()
    }

    fun cancel(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        refreshActiveCount()
    }

    fun remove(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        persist()
    }

    fun fileExists(context: Context, item: DownloadItem): Boolean {
        return try {
            when {
                item.uri?.startsWith("content://") == true -> {
                    context.contentResolver
                        .query(Uri.parse(item.uri), null, null, null, null)
                        ?.use { it.count > 0 } ?: false
                }
                item.savedPath.isNotBlank() -> File(item.savedPath).exists()
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun refreshActiveCount() {
        _activeCount.value = _items.value.count { it.isActive }
    }

    private fun parse(raw: String?): List<DownloadItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val status = runCatching {
                    DownloadStatus.valueOf(o.getString("status"))
                }.getOrDefault(DownloadStatus.COMPLETED)
                DownloadItem(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    thumbnailUrl = o.optString("thumbnailUrl").takeIf { it.isNotEmpty() },
                    status = status,
                    progress = if (status == DownloadStatus.COMPLETED) 100 else 0,
                    fileName = o.optString("fileName"),
                    savedPath = o.optString("savedPath"),
                    uri = o.optString("uri").takeIf { it.isNotEmpty() },
                    mime = o.optString("mime", "application/octet-stream"),
                    error = o.optString("error").takeIf { it.isNotEmpty() },
                    downloadedBytes = o.optLong("sizeBytes"),
                    totalBytes = o.optLong("sizeBytes"),
                    timestamp = o.optLong("timestamp"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist() {
        val arr = JSONArray()
        _items.value
            .filter { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED }
            .forEach { e ->
                arr.put(JSONObject().apply {
                    put("id", e.id)
                    put("title", e.title)
                    put("thumbnailUrl", e.thumbnailUrl)
                    put("status", e.status.name)
                    put("fileName", e.fileName)
                    put("savedPath", e.savedPath)
                    put("uri", e.uri)
                    put("mime", e.mime)
                    put("error", e.error)
                    put("sizeBytes", e.downloadedBytes)
                    put("timestamp", e.timestamp)
                })
            }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
    }

    private const val KEY = "items"
}
