package mint.app.resolution.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mint.app.core.util.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

data class MusicCandidate(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int?,
)

object YouTubeMusicSearch {

    private const val TAG = "YouTubeMusicSearch"
    private const val ENDPOINT = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
    private const val ORIGIN = "https://music.youtube.com"
    private const val CLIENT_NAME = "WEB_REMIX"
    private const val CLIENT_VERSION = "1.20260213.01.00"
    private const val CLIENT_ID = "67"
    private const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    private val TYPE_LABELS = setOf("song", "video", "album", "single", "ep", "playlist", "artist")
    private val TIME_RE = Regex("""^\d+:\d{2}(:\d{2})?$""")
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, limit: Int = 20): List<MusicCandidate> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val locale = Locale.getDefault()
        val hl = locale.language.takeIf { it.isNotBlank() } ?: "en"
        val gl = locale.country.takeIf { it.isNotBlank() } ?: "US"
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", CLIENT_NAME)
                        .put("clientVersion", CLIENT_VERSION)
                        .put("hl", hl)
                        .put("gl", gl),
                ),
            )
            .put("query", trimmed)
            .put("params", SONGS_FILTER)
            .toString()
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(body.toRequestBody(JSON_TYPE))
            .header("Content-Type", "application/json")
            .header("X-YouTube-Client-Name", CLIENT_ID)
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .header("X-Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("Accept-Language", "$hl,en;q=0.8")
            .header("User-Agent", UA)
            .build()
        val text = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.onFailure { Logger.w(TAG, "search failed: ${it.message}") }.getOrNull()
        if (text.isNullOrBlank()) {
            Logger.w(TAG, "search: empty response for '$trimmed'")
            return@withContext emptyList()
        }
        parse(text, limit)
    }

    private fun parse(text: String, limit: Int): List<MusicCandidate> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs") ?: return emptyList()
        val out = ArrayList<MusicCandidate>(limit)
        val seen = HashSet<String>()
        for (t in 0 until tabs.length()) {
            val sections = tabs.optJSONObject(t)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: continue
            for (s in 0 until sections.length()) {
                val shelf = sections.optJSONObject(s)?.optJSONObject("musicShelfRenderer") ?: continue
                val items = shelf.optJSONArray("contents") ?: continue
                for (i in 0 until items.length()) {
                    val renderer = items.optJSONObject(i)
                        ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    val candidate = toCandidate(renderer) ?: continue
                    if (!seen.add(candidate.videoId)) continue
                    out.add(candidate)
                    if (out.size >= limit) return out
                }
            }
        }
        return out
    }

    private fun toCandidate(renderer: JSONObject): MusicCandidate? {
        val videoId = renderer.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: renderer.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: return null
        val columns = renderer.optJSONArray("flexColumns") ?: return null
        val title = columnRuns(columns, 0).joinToString("").trim()
        if (title.isBlank()) return null
        val tokens = splitBySeparator(columnRuns(columns, 1)).toMutableList()
        if (tokens.isNotEmpty() && tokens.first().lowercase() in TYPE_LABELS) tokens.removeAt(0)
        var durationSec: Int? = null
        if (tokens.isNotEmpty() && TIME_RE.matches(tokens.last())) {
            durationSec = parseTime(tokens.removeAt(tokens.size - 1))
        }
        val artist = tokens.getOrNull(0).orEmpty()
        val album = tokens.getOrNull(1).orEmpty()
        return MusicCandidate(videoId, title, artist, album, durationSec)
    }

    private fun columnRuns(columns: JSONArray, index: Int): List<String> {
        val runs = columns.optJSONObject(index)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs") ?: return emptyList()
        return (0 until runs.length()).mapNotNull { runs.optJSONObject(it)?.optString("text") }
    }

    private fun splitBySeparator(runs: List<String>): List<String> {
        val out = ArrayList<String>(runs.size)
        val current = StringBuilder()
        for (run in runs) {
            if (run.trim() == "•") {
                val token = current.toString().trim()
                if (token.isNotEmpty()) out.add(token)
                current.setLength(0)
            } else {
                current.append(run)
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }

    private fun parseTime(value: String): Int? {
        val parts = value.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> null
        }
    }
}
