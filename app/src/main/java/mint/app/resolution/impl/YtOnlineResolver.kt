package mint.app.resolution.impl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mint.app.core.model.MediaFormat
import mint.app.core.model.MediaItem
import mint.app.core.util.Logger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

object YtOnlineResolver {

    private const val TAG = "YtOnlineResolver"
    private const val ORIGIN = "https://frame.y2meta-uk.com"
    private const val CNV = "https://cnv.cx"
    private const val PREFIX = "online:"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"

    private val VIDEO_QUALITIES = listOf("1080", "720", "360", "240", "144")
    private val AUDIO_QUALITIES = listOf("320", "256", "128")

    suspend fun resolve(url: String): MediaItem = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(url) ?: throw Exception("Unsupported YouTube link")
        Logger.d(TAG, "resolve: id=$videoId")
        val meta = fetchMeta(videoId)

        MediaItem(
            originalUrl = url,
            title = meta.title,
            uploader = meta.uploader,
            thumbnailUrl = meta.thumbnail,
            durationText = "",
            isMusicOnly = false,
            streamType = "VIDEO",
            platform = "youtube",
            videoOptions = VIDEO_QUALITIES.map { quality ->
                MediaFormat(
                    label = "${quality}p · mp4",
                    format = "mp4",
                    formatId = PREFIX + "mp4:" + quality,
                    url = "",
                    estimatedSizeBytes = 0,
                    hasAudio = true,
                    httpHeaders = mediaHeaders(),
                )
            },
            audioOptions = AUDIO_QUALITIES.map { quality ->
                MediaFormat(
                    label = "MP3 ${quality}kbps",
                    format = "mp3",
                    formatId = PREFIX + "mp3:" + quality,
                    url = "",
                    estimatedSizeBytes = 0,
                    hasAudio = true,
                    httpHeaders = mediaHeaders(),
                )
            },
            directDownload = true,
        )
    }

    suspend fun directUrl(url: String, formatId: String): String = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(url) ?: throw Exception("Unsupported YouTube link")
        val parts = formatId.removePrefix(PREFIX).split(":")
        val format = parts.getOrElse(0) { "mp4" }
        val quality = parts.getOrElse(1) { "720" }
        Logger.d(TAG, "directUrl: id=$videoId format=$format quality=$quality")
        val key = fetchKey(videoId)
        convert(key, videoId, format, quality)
    }

    private fun fetchKey(videoId: String): String {
        val json = httpGet("$CNV/v2/sanity/key?id=$videoId")
        val key = JSONObject(json).optString("key")
        if (key.isBlank()) throw Exception("Online converter rejected the request")
        return key
    }

    private fun convert(key: String, videoId: String, format: String, quality: String): String {
        val audioBitrate = if (format == "mp4") "128" else quality
        val videoQuality = if (format == "mp3") "720" else quality
        val body = buildString {
            append("link=").append(encode("https://youtu.be/$videoId"))
            append("&format=").append(encode(format))
            append("&audioBitrate=").append(encode(audioBitrate))
            append("&videoQuality=").append(encode(videoQuality))
            append("&filenameStyle=pretty")
            append("&vCodec=h264")
        }
        val json = httpPost("$CNV/v2/converter", body, mapOf("key" to key))
        val url = JSONObject(json).optString("url")
        if (url.isBlank()) throw Exception("Online converter returned no link")
        return url
    }

    private data class Meta(val title: String, val uploader: String, val thumbnail: String?)

    private fun fetchMeta(videoId: String): Meta {
        return try {
            val json = httpGet(
                "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json",
            )
            val obj = JSONObject(json)
            Meta(
                title = obj.optString("title").ifBlank { "YouTube video" },
                uploader = obj.optString("author_name").ifBlank { "Unknown" },
                thumbnail = obj.optString("thumbnail_url").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            Logger.w(TAG, "fetchMeta failed", e)
            Meta("YouTube video", "Unknown", "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
        }
    }

    private fun mediaHeaders(): Map<String, String> = mapOf(
        "Referer" to "$ORIGIN/",
        "Origin" to ORIGIN,
    )

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun httpGet(url: String): String {
        val conn = openConnection(url)
        conn.requestMethod = "GET"
        return readBody(conn)
    }

    private fun httpPost(url: String, body: String, headers: Map<String, String>): String {
        val conn = openConnection(url)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return readBody(conn)
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Origin", ORIGIN)
            setRequestProperty("Referer", "$ORIGIN/")
        }

    private fun readBody(conn: HttpURLConnection): String {
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception("Online converter HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    fun extractVideoId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path ?: ""
        if (host == "youtu.be") {
            return path.trim('/').substringBefore('/').takeIf { it.isNotBlank() }
        }
        if (host.endsWith("youtube.com") || host == "youtube-nocookie.com") {
            val query = uri.query ?: ""
            query.split("&").firstOrNull { it.startsWith("v=") }?.let { pair ->
                return pair.substringAfter("v=").takeIf { it.isNotBlank() }
            }
            val segments = path.trim('/').split("/")
            val kind = segments.firstOrNull()
            if (kind in listOf("shorts", "embed", "live", "v")) {
                return segments.getOrNull(1)?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }
}
