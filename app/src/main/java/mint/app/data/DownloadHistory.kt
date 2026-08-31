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

data class DownloadEntry(
    val id: String,
    val title: String,
    val fileName: String,
    val savedPath: String,
    val uri: String?,
    val mime: String,
    val thumbnailUrl: String?,
    val sizeBytes: Long,
    val timestamp: Long,
)

object DownloadHistory {

    private val _entries = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val entries: StateFlow<List<DownloadEntry>> = _entries.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun load(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences("mint_downloads", Context.MODE_PRIVATE)
        }
        _entries.value = parse(prefs?.getString(KEY, null))
    }

    fun add(context: Context, entry: DownloadEntry) {
        load(context)
        _entries.value = listOf(entry) + _entries.value
        persist()
    }

    fun remove(context: Context, id: String) {
        load(context)
        _entries.value = _entries.value.filterNot { it.id == id }
        persist()
    }

    fun fileExists(context: Context, entry: DownloadEntry): Boolean {
        return try {
            when {
                entry.uri?.startsWith("content://") == true -> {
                    context.contentResolver
                        .query(Uri.parse(entry.uri), null, null, null, null)
                        ?.use { it.count > 0 } ?: false
                }
                entry.savedPath.isNotBlank() -> File(entry.savedPath).exists()
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun parse(raw: String?): List<DownloadEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                DownloadEntry(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    fileName = o.getString("fileName"),
                    savedPath = o.optString("savedPath"),
                    uri = o.optString("uri").takeIf { it.isNotEmpty() },
                    mime = o.optString("mime", "application/octet-stream"),
                    thumbnailUrl = o.optString("thumbnailUrl").takeIf { it.isNotEmpty() },
                    sizeBytes = o.optLong("sizeBytes"),
                    timestamp = o.optLong("timestamp"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist() {
        val arr = JSONArray()
        _entries.value.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("title", e.title)
                put("fileName", e.fileName)
                put("savedPath", e.savedPath)
                put("uri", e.uri)
                put("mime", e.mime)
                put("thumbnailUrl", e.thumbnailUrl)
                put("sizeBytes", e.sizeBytes)
                put("timestamp", e.timestamp)
            })
        }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
    }

    private const val KEY = "entries"
}
