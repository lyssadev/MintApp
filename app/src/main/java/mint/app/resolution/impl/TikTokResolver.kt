package mint.app.resolution.impl

import android.content.Context
import android.util.Log
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

    private const val TAG = "TikTokResolver"

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val VIDEO_RE = Regex("https?://(?:www\\.)?tiktok\\.com/@[\\w.-]+/video/(\\d+)/?")
    private val PHOTO_RE = Regex("https?://(?:www\\.)?tiktok\\.com/@[\\w.-]+/photo/(\\d+)/?")
    private val ID_RE = Regex("https?://[^/]+/(?:video|photo)/(\\d+)/?")
    private val SHARE_RE = Regex("https?://(?:www\\.)?tiktok\\.com/t/[A-Za-z0-9_-]+/?")
    private val MOBILE_RE = Regex("https?://m\\.tiktok\\.com/v/\\d+/?")
    private val SHORT_RE = Regex("https?://vm\\.tiktok\\.com/[\\w]+/?")
    private val TIKTOKV_RE = Regex("https?://www\\.tiktokv?\\.com/share/video/\\d+/?")

    @Volatile private var appContext: Context? = null

    override fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override fun supports(url: String): Boolean = when {
        url.contains("tiktok.com") -> true
        url.contains("tiktokv.com") -> true
        else -> false
    }

    override suspend fun resolve(url: String): MediaItem = withContext(Dispatchers.IO) {
        Log.d(TAG, "resolve: url=$url")
        val finalUrl = resolveShortUrl(url)
        Log.d(TAG, "resolve: finalUrl=$finalUrl")
        val videoMatch = VIDEO_RE.find(finalUrl)
        val photoMatch = PHOTO_RE.find(finalUrl)
        val idMatch = ID_RE.find(finalUrl)
        Log.d(TAG, "resolve: VIDEO_RE=$videoMatch PHOTO_RE=$photoMatch ID_RE=$idMatch")
        val itemId = videoMatch?.groupValues?.get(1)
            ?: photoMatch?.groupValues?.get(1)
            ?: idMatch?.groupValues?.get(1)
            ?: throw Exception("Could not extract TikTok video/photo ID from URL: $finalUrl")
        Log.d(TAG, "resolve: itemId=$itemId")

        val html = fetchPage(finalUrl)
        Log.d(TAG, "resolve: html length=${html.length}")
        val data = extractUniversalData(html)
        if (data == null) {
            Log.w(TAG, "resolve: __UNIVERSAL_DATA_FOR_REHYDRATION__ not found in HTML")
            Log.d(TAG, "resolve: first 500 chars of HTML: ${html.take(500)}")
            throw Exception("TikTok page blocked or challenge required. Try logging in from Settings → Connections → TikTok.")
        }
        Log.d(TAG, "resolve: data extracted successfully")

        buildItem(finalUrl, data, itemId)
    }

    private fun resolveShortUrl(url: String): String {
        val trimmed = url.trim()
        if (!SHARE_RE.matches(trimmed) && !SHORT_RE.matches(trimmed) &&
            !MOBILE_RE.matches(trimmed) && !TIKTOKV_RE.matches(trimmed)
        ) {
            return trimmed
        }
        Log.d(TAG, "resolveShortUrl: following redirects for $trimmed")
        val request = Request.Builder()
            .url(trimmed)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .build()
        return client.newCall(request).execute().use { resp ->
            val resolved = resp.request.url.toString()
            Log.d(TAG, "resolveShortUrl: status=${resp.code} resolved=$resolved")
            resolved
        }
    }

    private fun fetchPage(url: String): String {
        Log.d(TAG, "fetchPage: url=$url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Upgrade-Insecure-Requests", "1")
            .build()
        return client.newCall(request).execute().use { resp ->
            Log.d(TAG, "fetchPage: status=${resp.code} contentType=${resp.header("Content-Type")}")
            if (!resp.isSuccessful) throw Exception("Failed to fetch TikTok page: ${resp.code}")
            resp.body?.string() ?: throw Exception("Empty response from TikTok")
        }
    }

    private fun extractUniversalData(html: String): JSONObject? {
        if (html.contains("Please wait") || html.contains("_wafchallengeid")) {
            Log.w(TAG, "extractUniversalData: WAF challenge detected (Please wait / _wafchallengeid)")
            return null
        }
        val regex = Regex(
            """<script[^>]*id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = regex.find(html)
        if (match == null) {
            Log.w(TAG, "extractUniversalData: regex did not find __UNIVERSAL_DATA_FOR_REHYDRATION__ script tag")
            return null
        }
        val raw = match.groupValues[1]
        return runCatching { JSONObject(raw) }
            .onSuccess { Log.d(TAG, "extractUniversalData: parsed JSON, keys=${it.length()}") }
            .onFailure { Log.w(TAG, "extractUniversalData: failed to parse JSON: ${it.message}") }
            .getOrNull()
    }

    private fun buildItem(url: String, data: JSONObject, itemId: String): MediaItem {
        val scope = data.optJSONObject("__DEFAULT_SCOPE__")
        if (scope == null) {
            Log.w(TAG, "buildItem: no __DEFAULT_SCOPE__; top keys=${data.keys().asSequence().toList()}")
            throw Exception("No scope in TikTok data")
        }
        val videoDetail = scope.optJSONObject("webapp.video-detail")
            ?: scope.optJSONObject("webapp.reflow.video.detail")
        if (videoDetail == null) {
            Log.w(TAG, "buildItem: no webapp.video-detail / webapp.reflow.video.detail; scope keys=${scope.keys().asSequence().toList()}")
            throw Exception("No video detail in TikTok data")
        }
        val itemStruct = videoDetail.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
            ?: videoDetail.optJSONObject("itemStruct")
        if (itemStruct == null) {
            Log.w(TAG, "buildItem: no itemInfo.itemStruct; video-detail keys=${videoDetail.keys().asSequence().toList()}")
            throw Exception("No item struct in TikTok data")
        }
        Log.d(TAG, "buildItem: itemStruct keys=${itemStruct.keys().asSequence().toList()}")

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