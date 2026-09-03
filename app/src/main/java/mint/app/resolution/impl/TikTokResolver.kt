package mint.app.resolution.impl

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mint.app.core.model.MediaFormat
import mint.app.core.model.MediaItem
import mint.app.core.prefs.ConnectionPreferences
import mint.app.core.util.Logger
import mint.app.resolution.Resolver
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object TikTokResolver : Resolver {

    private const val TAG = "TikTokResolver"

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val jar = ConcurrentHashMap<String, Cookie>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookies.forEach { jar[it.name] = it }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> = jar.values.toList()
        })
        .build()

    private val VIDEO_RE = Regex("https?://(?:www\\.)?tiktok\\.com/@[\\w.-]+/video/(\\d+)/?")
    private val PHOTO_RE = Regex("https?://(?:www\\.)?tiktok\\.com/@[\\w.-]+/photo/(\\d+)/?")
    private val ID_RE = Regex("https?://[^/]+/(?:video|photo)/(\\d+)/?")
    private val SHARE_RE = Regex("https?://(?:www\\.)?tiktok\\.com/t/[A-Za-z0-9_-]+/?")
    private val MOBILE_RE = Regex("https?://m\\.tiktok\\.com/v/\\d+/?")
    private val SHORT_RE = Regex("https?://(?:vm|vt)\\.tiktok\\.com/[A-Za-z0-9_-]+/?")
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
        Logger.d(TAG, "resolve: url=$url")
        jar.clear()
        val finalUrl = resolveShortUrl(url)
        Logger.d(TAG, "resolve: finalUrl=$finalUrl")
        if (VIDEO_RE.find(finalUrl) == null && PHOTO_RE.find(finalUrl) == null && ID_RE.find(finalUrl) == null) {
            Logger.w(TAG, "resolve: no video/photo pattern matched in $finalUrl")
            throw Exception("Could not extract TikTok video/photo ID from URL: $finalUrl")
        }

        val html = fetchPage(finalUrl)
        Logger.d(TAG, "resolve: html length=${html.length}")
        val data = extractUniversalData(html)
        if (data == null) {
            Logger.w(TAG, "resolve: data extraction failed, page may be blocked")
            throw Exception("TikTok page blocked or challenge required. Try logging in from Settings → Connections → TikTok.")
        }
        Logger.d(TAG, "resolve: data extracted successfully, keys=${data.keys().asSequence().toList()}")

        buildItem(finalUrl, data)
    }

    private fun resolveShortUrl(url: String): String {
        val trimmed = url.trim()
        if (!SHARE_RE.matches(trimmed) && !SHORT_RE.matches(trimmed) &&
            !MOBILE_RE.matches(trimmed) && !TIKTOKV_RE.matches(trimmed)
        ) {
            return trimmed
        }
        Logger.d(TAG, "resolveShortUrl: following redirects for $trimmed")
        val request = Request.Builder()
            .url(trimmed)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .build()
        return client.newCall(request).execute().use { resp ->
            val resolved = resp.request.url.toString()
            Logger.d(TAG, "resolveShortUrl: status=${resp.code} resolved=$resolved")
            resolved
        }
    }

    private fun fetchPage(url: String): String {
        Logger.d(TAG, "fetchPage: url=$url")
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
            Logger.d(TAG, "fetchPage: status=${resp.code} contentType=${resp.header("Content-Type")}")
            if (!resp.isSuccessful) throw Exception("Failed to fetch TikTok page: ${resp.code}")
            resp.body?.string() ?: throw Exception("Empty response from TikTok")
        }
    }

    private fun extractUniversalData(html: String): JSONObject? {
        if (html.contains("Please wait") || html.contains("_wafchallengeid")) {
            Logger.w(TAG, "extractUniversalData: WAF challenge detected (Please wait / _wafchallengeid)")
            return null
        }
        val regex = Regex(
            """<script[^>]*id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val match = regex.find(html)
        if (match == null) {
            Logger.w(TAG, "extractUniversalData: __UNIVERSAL_DATA_FOR_REHYDRATION__ script tag not found")
            return null
        }
        val raw = match.groupValues[1]
        return runCatching { JSONObject(raw) }
            .onSuccess { Logger.d(TAG, "extractUniversalData: parsed JSON, keys=${it.length()}") }
            .onFailure { Logger.w(TAG, "extractUniversalData: failed to parse JSON: ${it.message}") }
            .getOrNull()
    }

    private fun buildCookieHeader(): String {
        val seen = LinkedHashMap<String, String>()
        jar.values.forEach { seen[it.name] = it.value }
        appContext?.let { ctx ->
            ConnectionPreferences.tiktokCookies(ctx).forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) seen.putIfAbsent(k, v)
            }
        }
        return seen.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun buildItem(url: String, data: JSONObject): MediaItem {
        val scope = data.optJSONObject("__DEFAULT_SCOPE__")
        if (scope == null) {
            Logger.w(TAG, "buildItem: no __DEFAULT_SCOPE__; top keys=${data.keys().asSequence().toList()}")
            throw Exception("No scope in TikTok data")
        }
        val videoDetail = scope.optJSONObject("webapp.video-detail")
            ?: scope.optJSONObject("webapp.reflow.video.detail")
        if (videoDetail == null) {
            Logger.w(TAG, "buildItem: no video detail; scope keys=${scope.keys().asSequence().toList()}")
            throw Exception("No video detail in TikTok data")
        }
        val itemStruct = videoDetail.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
            ?: videoDetail.optJSONObject("itemStruct")
        if (itemStruct == null) {
            Logger.w(TAG, "buildItem: no itemStruct; video-detail keys=${videoDetail.keys().asSequence().toList()}")
            throw Exception("No item struct in TikTok data")
        }
        Logger.d(TAG, "buildItem: itemStruct keys=${itemStruct.keys().asSequence().toList()}")
        val isImage = itemStruct.optJSONObject("imagePost") != null

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
            Logger.d(TAG, "buildItem: image post, ${imageOptions.size} images")
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
        val cookieHeader = buildCookieHeader()
        val headers = buildMap {
            put("User-Agent", UA)
            put("Referer", url)
            put("Accept", "*/*")
            if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
        }
        val videoUrls = buildList {
            video.optJSONArray("bitrateInfo")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)
                        ?.optJSONObject("PlayAddr")
                        ?.optJSONArray("UrlList")
                        ?.optString(0)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { add(it) }
                }
            }
            video.optString("playAddr").takeIf { it.isNotBlank() }?.let { add(it) }
            video.optString("downloadAddr").takeIf { it.isNotBlank() }?.let { add(it) }
        }
        val videoUrl = videoUrls.firstOrNull()
            ?: throw Exception("No video URL found in TikTok post")
        Logger.d(TAG, "buildItem: videoUrl=$videoUrl")

        val duration = video.optInt("duration", 0)
        val height = video.optInt("height", 0)
        val label = if (height > 0) "${height}p · mp4" else "Video · mp4"
        Logger.d(TAG, "buildItem: video height=${height}p duration=${duration}s cookie=${cookieHeader.isNotBlank()}")

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
                    url = videoUrl,
                    estimatedSizeBytes = 0,
                    hasAudio = true,
                    httpHeaders = headers,
                ),
            ),
            audioOptions = emptyList(),
            imageOptions = emptyList(),
            gifOptions = emptyList(),
        )
    }
}