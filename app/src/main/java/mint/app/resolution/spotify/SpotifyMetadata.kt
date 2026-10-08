package mint.app.resolution.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mint.app.core.util.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

data class SpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String?,
)

data class SpotifyCollection(
    val type: String,
    val id: String,
    val title: String,
    val owner: String,
    val coverUrl: String?,
    val tracks: List<SpotifyTrack>,
)

object SpotifyMetadata {

    private const val TAG = "SpotifyMetadata"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val NEXT_DATA = Regex(
        """<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val ID_RE = Regex("""^[A-Za-z0-9]{22}$""")
    private val HOSTS = setOf("open.spotify.com", "play.spotify.com")
    private val TYPES = setOf("track", "album", "playlist")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun isSpotify(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()
        if (host != null && host in HOSTS) return true
        return url.trim().startsWith("spotify:")
    }

    fun isCollection(url: String): Boolean {
        val parsed = parseLink(url) ?: return false
        return parsed.first == "album" || parsed.first == "playlist"
    }

    fun parseLink(raw: String): Pair<String, String>? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("spotify:")) {
            val parts = trimmed.split(":")
            if (parts.size < 3) return null
            val type = parts[1].lowercase()
            val id = parts[2].substringBefore('?')
            if (type in TYPES && ID_RE.matches(id)) return type to id
            return null
        }
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in HOSTS) return null
        val segments = (uri.path ?: "").trim('/').split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        var index = 0
        if (segments.size >= 2 && segments[0].startsWith("intl-")) index = 1
        val type = segments.getOrNull(index)?.lowercase() ?: return null
        val id = segments.getOrNull(index + 1)?.substringBefore('?') ?: return null
        if (type !in TYPES || !ID_RE.matches(id)) return null
        return type to id
    }

    suspend fun fetch(type: String, id: String): SpotifyCollection = withContext(Dispatchers.IO) {
        val embedUrl = "https://open.spotify.com/embed/$type/$id"
        val html = httpGet(embedUrl)
            ?: throw Exception("Could not read this Spotify link")
        val payload = NEXT_DATA.find(html)?.groupValues?.get(1)
            ?: throw Exception("Could not read this Spotify link")
        val entity = runCatching {
            JSONObject(payload)
                .getJSONObject("props")
                .getJSONObject("pageProps")
                .getJSONObject("state")
                .getJSONObject("data")
                .getJSONObject("entity")
        }.getOrNull() ?: throw Exception("Could not read this Spotify link")

        val entityType = entity.optString("type").ifBlank { type }
        val title = entity.optString("title").ifBlank { entity.optString("name") }
        val owner = entity.optString("subtitle").ifBlank { artistLine(entity) }
        val cover = coverFrom(entity) ?: oembedCover(type, id)
        val tracks = when (entityType) {
            "track" -> listOfNotNull(singleTrack(entity, cover))
            else -> trackList(entity, owner, cover)
        }
        Logger.d(TAG, "fetch: type=$entityType id=$id tracks=${tracks.size}")
        SpotifyCollection(
            type = entityType,
            id = id,
            title = title.ifBlank { "Spotify" },
            owner = owner,
            coverUrl = cover,
            tracks = tracks,
        )
    }

    private fun singleTrack(entity: JSONObject, cover: String?): SpotifyTrack? {
        val id = entity.optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = entity.optString("name").ifBlank { entity.optString("title") }
        if (title.isBlank()) return null
        return SpotifyTrack(
            id = id,
            title = title,
            artist = artistLine(entity),
            album = "",
            durationMs = entity.optLong("duration", 0L),
            coverUrl = cover,
        )
    }

    private fun trackList(entity: JSONObject, artistFallback: String, cover: String?): List<SpotifyTrack> {
        val array = entity.optJSONArray("trackList") ?: return emptyList()
        val out = ArrayList<SpotifyTrack>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val uri = obj.optString("uri")
            val id = uri.substringAfterLast(':')
            if (!ID_RE.matches(id)) continue
            val title = obj.optString("title").ifBlank { obj.optString("name") }
            if (title.isBlank()) continue
            out.add(
                SpotifyTrack(
                    id = id,
                    title = title,
                    artist = obj.optString("subtitle").ifBlank { artistFallback },
                    album = "",
                    durationMs = obj.optLong("duration", 0L),
                    coverUrl = cover,
                )
            )
        }
        return out
    }

    private fun artistLine(entity: JSONObject): String {
        val array = entity.optJSONArray("artists") ?: return ""
        return (0 until array.length())
            .mapNotNull { array.optJSONObject(it)?.optString("name")?.takeIf { name -> name.isNotBlank() } }
            .joinToString(", ")
    }

    private fun coverFrom(entity: JSONObject): String? {
        val sources = entity.optJSONObject("coverArt")?.optJSONArray("sources") ?: return null
        return (0 until sources.length())
            .mapNotNull { sources.optJSONObject(it)?.optString("url")?.takeIf { url -> url.isNotBlank() } }
            .lastOrNull()
    }

    private fun oembedCover(type: String, id: String): String? {
        val body = httpGet("https://open.spotify.com/oembed?url=https://open.spotify.com/$type/$id") ?: return null
        return runCatching {
            JSONObject(body).optString("thumbnail_url").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun httpGet(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/json")
            .header("Accept-Language", "en")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.onFailure { Logger.w(TAG, "httpGet failed: ${it.message}") }.getOrNull()
}
