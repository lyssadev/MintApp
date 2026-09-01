package mint.app.resolution.impl

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mint.app.core.model.MediaFormat
import mint.app.core.model.MediaItem
import mint.app.core.prefs.ConnectionPreferences
import mint.app.resolution.Resolver
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object TikTokResolver : Resolver {

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val VIDEO_RE = Regex("https?://(?:www\\.)?tiktok\\.com/@[\\w.-]+/video/(\\d+)")
    private val PHOTO_RE = Regex("https?://(?:www\\.)?tiktok\\.com/@[\\w.-]+/photo/(\\d+)")
    private val SHORT_RE = Regex("https?://vm\\.tiktok\\.com/[\\w]+")

    @Volatile private var appContext: Context? = null

    override fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override fun supports(url: String): Boolean =
        VIDEO_RE.containsMatchIn(url) || PHOTO_RE.containsMatchIn(url) || SHORT_RE.containsMatchIn(url)

    override suspend fun resolve(url: String): MediaItem = withContext(Dispatchers.IO) {
        val finalUrl = resolveShortUrl(url)
        val itemId = VIDEO_RE.find(finalUrl)?.groupValues?.get(1)
            ?: PHOTO_RE.find(finalUrl)?.groupValues?.get(1)
            ?: throw Exception("Could not extract TikTok video/photo ID")

        val html = fetchPage(finalUrl)
        val data = extractUniversalData(html)
            ?: throw Exception("TikTok page blocked or challenge required; falling back to yt-dlp")

        buildItem(finalUrl, data, itemId)
    }

    private fun resolveShortUrl(url: String): String {
        if (!SHORT_RE.matches(url.trim())) return url
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .build()
        return client.newCall(request).execute().use { resp ->
            resp.request.url.toString()
        }
    }

    private fun fetchPage(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Upgrade-Insecure-Requests", "1")
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Failed to fetch TikTok page: ${resp.code}")
            resp.body?.string() ?: throw Exception("Empty response from TikTok")
        }
    }

    private fun extractUniversalData(html: String): JSONObject? {
        if (html.contains("Please wait") || html.contains("_wafchallengeid")) return null
        val regex = Regex(
            """<script[^>]*id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = regex.find(html) ?: return null
        val raw = match.groupValues[1]
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun buildItem(url: String, data: JSONObject, itemId: String): MediaItem {
        val scope = data.optJSONObject("__DEFAULT_SCOPE__")
            ?: throw Exception("No scope in TikTok data")
        val videoDetail = scope.optJSONObject("webapp.video-detail")
            ?: throw Exception("No video detail in TikTok data")
        val itemStruct = videoDetail.optJSONObject("itemInfo")
            ?.optJSONObject("itemStruct")
            ?: throw Exception("No item struct in TikTok data")

        val desc = itemStruct.optString("desc", "TikTok post").take(120)
        val author = itemStruct.optJSONObject("author")?.optString("uniqueId") ?: "tiktok"
        val thumbnail = itemStruct.optJSONObject("video")?.optString("dynamicCover")
            ?: itemStruct.optJSONObject("video")?.optString("cover")

        val imagePost = itemStruct.optJSONObject("imagePost")
        if (imagePost != null) {
            val images = imagePost.optJSONArray("images") ?: throw Exception("No images array in imagePost")
            val imageOptions = (0 until images.length()).mapNotNull { i ->
                val img = images.optJSONObject(i) ?: return@mapNotNull null
                val urlList = img.optJSONObject("imageURL")
                    ?.optJSONArray("urlList") ?: return@mapNotNull null
                val imgUrl = urlList.optString(0) ?: return@mapNotNull null
                MediaFormat(
                    label = "Image ${i + 1} · jpg",
                    format = "jpg",
                    formatId = "",
                    url = imgUrl,
                    estimatedSizeBytes = 0,
                    hasAudio = false,
                )
            }
            if (imageOptions.isEmpty()) throw Exception("No images found in TikTok photo post")
            return MediaItem(
                originalUrl = url,
                title = desc,
                uploader = author,
                thumbnailUrl = thumbnail,
                durationText = "",
                isMusicOnly = false,
                streamType = "IMAGE",
                platform = "tiktok",
                videoOptions = emptyList(),
                audioOptions = emptyList(),
                imageOptions = imageOptions,
                gifOptions = emptyList(),
            )
        }

        val video = itemStruct.optJSONObject("video")
            ?: throw Exception("No video data in TikTok post")
        val playAddr = video.optString("playAddr")
        if (playAddr.isBlank()) throw Exception("No video URL found in TikTok post")

        val duration = video.optInt("duration", 0)
        val height = video.optInt("height", 0)
        val label = if (height > 0) "${height}p · mp4" else "Video · mp4"

        return MediaItem(
            originalUrl = url,
            title = desc,
            uploader = author,
            thumbnailUrl = thumbnail,
            durationText = if (duration > 0) {
                "%d:%02d".format(duration / 60, duration % 60)
            } else "",
            isMusicOnly = false,
            streamType = "VIDEO",
            platform = "tiktok",
            videoOptions = listOf(
                MediaFormat(
                    label = label,
                    format = "mp4",
                    formatId = "",
                    url = playAddr,
                    estimatedSizeBytes = 0,
                    hasAudio = true,
                ),
            ),
            audioOptions = emptyList(),
            imageOptions = emptyList(),
            gifOptions = emptyList(),
        )
    }
}