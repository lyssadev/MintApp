package mint.app.resolution.impl

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mint.app.core.model.MediaFormat
import mint.app.core.model.MediaItem
import mint.app.core.util.Logger
import mint.app.resolution.Resolver
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

object XResolver : Resolver {

    private const val TAG = "XResolver"

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val SYNDICATION_URL = "https://cdn.syndication.twimg.com/tweet-result"
    private const val HLS_FORMAT_ID = "hls"

    private val STATUS_RE = Regex("""/(?:status|statuses)/(\d+)""")
    private val VIDEO_DIM_RE = Regex("""/(\d{2,5})x(\d{2,5})/""")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun initialize(context: Context) = Unit

    override fun supports(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        val isX = host == "x.com" || host == "twitter.com" ||
            host.endsWith(".x.com") || host.endsWith(".twitter.com")
        return isX && STATUS_RE.containsMatchIn(url)
    }

    override suspend fun resolve(url: String): MediaItem = withContext(Dispatchers.IO) {
        Logger.d(TAG, "resolve: url=$url")
        val statusId = STATUS_RE.find(url)?.groupValues?.get(1)
            ?: throw Exception("Could not extract X status ID from URL")
        Logger.d(TAG, "resolve: statusId=$statusId")

        val data = fetchTweet(statusId)
            ?: throw Exception("X post is unavailable or private")

        buildItem(url, statusId, data)
    }

    private fun fetchTweet(statusId: String): JSONObject? {
        val request = Request.Builder()
            .url("$SYNDICATION_URL?id=$statusId&token=${tokenFor(statusId)}&lang=en")
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .build()
        return client.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: return null
            Logger.d(TAG, "fetchTweet: status=${resp.code} length=${body.length}")
            if (!resp.isSuccessful) return null
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            if (json.optJSONObject("video") == null && json.optJSONArray("mediaDetails") == null) {
                Logger.w(TAG, "fetchTweet: no media in response for $statusId")
                return null
            }
            json
        }
    }

    private fun tokenFor(statusId: String): String = statusId.hashCode().toString(36)

    private fun buildItem(url: String, statusId: String, data: JSONObject): MediaItem {
        val text = data.optString("text").take(120).ifBlank { "X video" }
        val user = data.optJSONObject("user")
        val uploader = user?.optString("screen_name")
            ?.takeIf { it.isNotBlank() }
            ?: user?.optString("name")?.takeIf { it.isNotBlank() }
            ?: "x"

        val video = data.optJSONObject("video")
        val thumbnail = video?.optString("poster")?.takeIf { it.isNotBlank() }
        val durationMs = video?.optLong("durationMs", 0L)?.takeIf { it > 0 }
            ?: data.optJSONArray("mediaDetails")
                ?.optJSONObject(0)
                ?.optJSONObject("video_info")
                ?.optLong("duration_millis", 0L)
            ?: 0L

        val options = collectVideoOptions(data)
        if (options.isEmpty()) {
            Logger.w(TAG, "buildItem: no downloadable video for $statusId")
            throw Exception("No downloadable video found in this X post")
        }

        Logger.d(TAG, "buildItem: ${options.size} video option(s) for $statusId")
        return MediaItem(
            originalUrl = url,
            title = text,
            uploader = uploader,
            thumbnailUrl = thumbnail,
            durationText = if (durationMs > 0) formatDuration(durationMs) else "",
            isMusicOnly = false,
            streamType = "VIDEO",
            platform = "x",
            videoOptions = options,
            audioOptions = emptyList(),
            imageOptions = emptyList(),
            gifOptions = emptyList(),
        )
    }

    private fun collectVideoOptions(data: JSONObject): List<MediaFormat> {
        val variants = collectVariants(data)
        val headers = mapOf("Referer" to "https://x.com/")

        val mp4s = mp4Formats(variants, headers)
        if (mp4s.isNotEmpty()) return mp4s

        val hls = hlsUrl(variants) ?: return emptyList()
        Logger.d(TAG, "collectVideoOptions: only HLS available, deferring mux to yt-dlp")
        return listOf(
            MediaFormat(
                label = "Video · mp4 (HLS)",
                format = "mp4",
                formatId = HLS_FORMAT_ID,
                url = hls,
                estimatedSizeBytes = 0,
                hasAudio = true,
                httpHeaders = headers,
            ),
        )
    }

    private fun collectVariants(data: JSONObject): JSONArray {
        val out = JSONArray()
        val mediaDetails = data.optJSONArray("mediaDetails")
        if (mediaDetails != null) {
            for (i in 0 until mediaDetails.length()) {
                val media = mediaDetails.optJSONObject(i) ?: continue
                if (media.optString("type") != "video") continue
                val variants = media.optJSONObject("video_info")?.optJSONArray("variants") ?: continue
                for (j in 0 until variants.length()) {
                    variants.optJSONObject(j)?.let { out.put(it) }
                }
            }
        }
        if (out.length() == 0) {
            val variants = data.optJSONObject("video")?.optJSONArray("variants")
            if (variants != null) {
                for (j in 0 until variants.length()) {
                    variants.optJSONObject(j)?.let { out.put(it) }
                }
            }
        }
        return out
    }

    private fun hlsUrl(variants: JSONArray): String? {
        for (i in 0 until variants.length()) {
            val variant = variants.optJSONObject(i) ?: continue
            val type = variant.optString("content_type").ifBlank { variant.optString("type") }
            if (type == "application/x-mpegURL") {
                val url = variant.optString("url").ifBlank { variant.optString("src") }
                if (url.isNotBlank()) return url
            }
        }
        return null
    }

    private fun mp4Formats(variants: JSONArray, headers: Map<String, String>): List<MediaFormat> {
        val formats = mutableListOf<MediaFormat>()
        for (i in 0 until variants.length()) {
            val variant = variants.optJSONObject(i) ?: continue
            val type = variant.optString("content_type").ifBlank { variant.optString("type") }
            if (type != "video/mp4") continue
            val videoUrl = variant.optString("url").ifBlank { variant.optString("src") }
            if (videoUrl.isBlank()) continue
            val bitrate = variant.optLong("bitrate", 0L)
            val dims = VIDEO_DIM_RE.find(videoUrl)
            val height = dims?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val label = if (height > 0) "${height}p · mp4" else "Video · mp4"
            formats += MediaFormat(
                label = label,
                format = "mp4",
                formatId = if (bitrate > 0) bitrate.toString() else "",
                url = videoUrl,
                estimatedSizeBytes = 0,
                hasAudio = true,
                httpHeaders = headers,
            )
        }
        return formats
            .sortedByDescending { it.formatId.toLongOrNull() ?: 0L }
            .distinctBy { it.label }
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
