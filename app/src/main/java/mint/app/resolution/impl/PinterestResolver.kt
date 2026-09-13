package mint.app.resolution.impl

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mint.app.core.model.MediaFormat
import mint.app.core.model.MediaItem
import mint.app.core.prefs.ConnectionPreferences
import mint.app.core.util.Logger
import mint.app.resolution.LoginRequiredException
import mint.app.resolution.Resolver
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

object PinterestResolver : Resolver {

    private const val TAG = "PinterestResolver"

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val API = "https://www.pinterest.com/resource/ApiResource/get/"

    private val PIN_RE = Regex("""/pin/(\d+)""")
    private val PIN_IT_RE = Regex("""pin\.it/[\w-]+""")
    private val HOST_RE = Regex("""(^|\.)pinterest\.[a-z.]+$""")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile private var appContext: Context? = null

    override fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override fun supports(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "pin.it" ||
            host == "pinterest.com" ||
            host.endsWith(".pinterest.com") ||
            HOST_RE.containsMatchIn(host)
    }

    override suspend fun resolve(url: String): MediaItem = withContext(Dispatchers.IO) {
        val ctx = appContext
            ?: throw Exception("Pinterest resolver not initialized")
        if (!ConnectionPreferences.isPinterestLinked(ctx)) {
            throw LoginRequiredException(
                "Pinterest login required. Open Settings → Connections to link your account.",
            )
        }
        Logger.d(TAG, "resolve: url=$url")

        val finalUrl = resolveShortUrl(url)
        Logger.d(TAG, "resolve: finalUrl=$finalUrl")
        val pinId = PIN_RE.find(finalUrl)?.groupValues?.get(1)
            ?: throw Exception("Could not extract Pinterest pin ID from URL: $finalUrl")

        val data = fetchPin(ctx, pinId)
            ?: throw Exception("Could not fetch Pinterest pin $pinId. It may be unavailable, private, or the session expired.")

        buildItem(finalUrl, pinId, data)
    }

    private fun resolveShortUrl(url: String): String {
        val trimmed = url.trim()
        if (!PIN_IT_RE.containsMatchIn(trimmed)) return trimmed
        Logger.d(TAG, "resolveShortUrl: resolving pin.it link $trimmed")
        val request = Request.Builder()
            .url(trimmed)
            .header("User-Agent", UA)
            .build()
        return client.newCall(request).execute().use { resp ->
            val resolved = resp.request.url.toString()
            Logger.d(TAG, "resolveShortUrl: resolved=$resolved")
            resolved
        }
    }

    private fun fetchPin(ctx: Context, pinId: String): JSONObject? {
        val cookies = ConnectionPreferences.pinterestCookies(ctx)
        val csrf = cookies["csrftoken"].orEmpty()
        val fields = buildFields()
        val data = JSONObject()
            .put("options", JSONObject()
                .put("url", "/v3/pins/$pinId/")
                .put("data", JSONObject().put("fields", JSONArray(fields))))
            .put("context", JSONObject())

        val body = FormBody.Builder()
            .add("source_url", "/pin/$pinId/")
            .add("data", data.toString())
            .build()

        val request = Request.Builder()
            .url(API)
            .post(body)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-Pinterest-Source-URL", "/pin/$pinId/")
            .header("Origin", "https://www.pinterest.com")
            .header("Referer", "https://www.pinterest.com/")
            .header("Cookie", buildCookieHeader(cookies))
            .apply {
                if (csrf.isNotBlank()) header("X-CSRFToken", csrf)
            }
            .build()

        return client.newCall(request).execute().use { resp ->
            val bodyText = resp.body?.string() ?: return null
            Logger.d(TAG, "fetchPin: status=${resp.code} length=${bodyText.length}")
            if (!resp.isSuccessful) {
                Logger.w(TAG, "fetchPin: HTTP ${resp.code}")
                return null
            }
            val json = runCatching { JSONObject(bodyText) }.getOrNull() ?: return null
            val d = json.optJSONObject("resource_response")?.optJSONObject("data")
            if (d == null || d.isNull("id")) {
                Logger.w(TAG, "fetchPin: no pin data in response")
                return null
            }
            d
        }
    }

    private fun buildCookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun buildFields(): List<String> = listOf(
        "pin.id",
        "pin.is_video",
        "pin.type",
        "pin.title",
        "pin.description",
        "pin.link",
        "pin.images",
        "pin.pinner()",
        "pin.board()",
        "pin.videos()",
        "video.id",
        "video.video_list",
        "pin.carousel_data()",
        "pincarouselslot.id",
        "pincarouselslot.details",
        "pincarouselslot.title",
        "pincarouselslot.link",
        "pincarouselslot.images[60x60,136x136,170x,236x,474x,564x,736x,600x315,orig]",
        "pincarouselslot.videos()",
        "pin.story_pin_data()",
        "pin.aggregated_pin_data()",
    )

    private fun buildItem(url: String, pinId: String, data: JSONObject): MediaItem {
        val title = data.optString("title").ifBlank { data.optString("description") }
            .ifBlank { "Pinterest pin" }.take(120)
        val pinner = data.optJSONObject("pinner")
        val uploader = pinner?.optString("username")?.takeIf { it.isNotBlank() }
            ?: pinner?.optString("full_name")?.takeIf { it.isNotBlank() }
            ?: "pinterest"
        val thumbnail = bestImage(data.optJSONObject("images"))

        val videoOptions = mutableListOf<MediaFormat>()
        val imageOptions = mutableListOf<MediaFormat>()
        val gifOptions = mutableListOf<MediaFormat>()

        val videoList = data.optJSONObject("videos")?.optJSONObject("video_list")
        if (videoList != null && videoList.length() > 0) {
            videoOptions += videoFromList(videoList, 0)
            Logger.d(TAG, "buildItem: video pin, ${videoOptions.size} video options")
        }

        val carousel = data.optJSONObject("carousel_data")?.optJSONArray("carousel_slots")
        if (carousel != null && carousel.length() > 0) {
            for (i in 0 until carousel.length()) {
                val slot = carousel.optJSONObject(i) ?: continue
                val slotVideos = slot.optJSONObject("videos")?.optJSONObject("video_list")
                if (slotVideos != null && slotVideos.length() > 0) {
                    videoOptions += videoFromList(slotVideos, i + 1)
                } else {
                    val img = bestImage(slot.optJSONObject("images"))
                    if (img != null) {
                        imageOptions += imageFormat("Image ${i + 1}", img)
                    }
                }
            }
            Logger.d(TAG, "buildItem: carousel pin, ${carousel.length()} slots")
        }

        val story = data.optJSONObject("story_pin_data")
        if (story != null) {
            val pages = story.optJSONArray("pages")
            if (pages != null && pages.length() > 0) {
                for (i in 0 until pages.length()) {
                    val page = pages.optJSONObject(i) ?: continue
                    val blocks = page.optJSONArray("blocks")
                    if (blocks != null) {
                        for (j in 0 until blocks.length()) {
                            val block = blocks.optJSONObject(j) ?: continue
                            val sig = block.optString("video_signature")
                            if (sig.isNotBlank() && sig != "null") {
                                videoOptions += hlsFromSignature(sig, i + 1)
                            } else {
                                val blockVideo = block.optJSONObject("video_data_v2")
                                    ?.optJSONObject("video_list")
                                if (blockVideo != null && blockVideo.length() > 0) {
                                    videoOptions += videoFromList(blockVideo, i + 1)
                                }
                            }
                        }
                    }
                    val pageImg = bestImage(page.optJSONObject("image")?.optJSONObject("images"))
                    if (pageImg != null) {
                        imageOptions += imageFormat("Page ${i + 1}", pageImg)
                    }
                }
                Logger.d(TAG, "buildItem: story pin, ${pages.length()} pages")
            }
        }

        val singleImage = if (videoOptions.isEmpty() && imageOptions.isEmpty() && gifOptions.isEmpty()) {
            val img = bestImage(data.optJSONObject("images"))
            if (img != null) {
                if (img.endsWith(".gif", ignoreCase = true)) {
                    gifOptions += gifFormat(img)
                } else {
                    imageOptions += imageFormat("Image", img)
                }
                true
            } else {
                false
            }
        } else {
            false
        }
        Logger.d(TAG, "buildItem: video=${videoOptions.size} image=${imageOptions.size} gif=${gifOptions.size} singleImage=$singleImage")

        if (videoOptions.isEmpty() && imageOptions.isEmpty() && gifOptions.isEmpty()) {
            throw Exception("No downloadable media found in this Pinterest pin")
        }

        val streamType = if (videoOptions.isNotEmpty()) "VIDEO" else "IMAGE"
        return MediaItem(
            originalUrl = url,
            title = title,
            uploader = uploader,
            thumbnailUrl = thumbnail,
            durationText = "",
            isMusicOnly = false,
            streamType = streamType,
            platform = "pinterest",
            videoOptions = videoOptions,
            audioOptions = emptyList(),
            imageOptions = imageOptions,
            gifOptions = gifOptions,
        )
    }

    private fun videoFromList(videoList: JSONObject, index: Int): List<MediaFormat> {
        val headers = mapOf("Referer" to "https://www.pinterest.com/")
        val out = mutableListOf<MediaFormat>()
        val keys = videoList.keys().asSequence().toList()
        for (key in keys) {
            val v = videoList.optJSONObject(key) ?: continue
            val vUrl = v.optString("url")
            if (vUrl.isBlank()) continue
            val isMp4 = vUrl.endsWith(".mp4", ignoreCase = true)
            val isM3u8 = vUrl.contains(".m3u8", ignoreCase = true)
            if (!isMp4 && !isM3u8) continue
            if (isMp4) {
                val lower = vUrl.lowercase()
                if (lower.contains("av1") || lower.contains("h265") || lower.contains("hevc")) continue
                val width = v.optInt("width", 0)
                val height = v.optInt("height", 0)
                val bitrate = v.optLong("bitrate", 0L)
                val label = if (height > 0) "${height}p · mp4" else "Video ${index + 1} · mp4"
                out += MediaFormat(
                    label = label,
                    format = "mp4",
                    formatId = if (bitrate > 0) bitrate.toString() else "",
                    url = vUrl,
                    estimatedSizeBytes = 0,
                    hasAudio = true,
                    httpHeaders = headers,
                )
            } else {
                out += MediaFormat(
                    label = "Video ${index + 1} · mp4 (HLS)",
                    format = "mp4",
                    formatId = "hls",
                    url = vUrl,
                    estimatedSizeBytes = 0,
                    hasAudio = true,
                    httpHeaders = headers,
                )
            }
        }
        val mp4 = out.filterNot { it.formatId == "hls" }
        val hls = out.filter { it.formatId == "hls" }
        return (if (mp4.isNotEmpty()) mp4 else hls)
            .sortedByDescending { it.formatId.toLongOrNull() ?: 0L }
            .distinctBy { it.label }
    }

    private fun hlsFromSignature(signature: String, index: Int): MediaFormat {
        val clean = signature.trim()
        val url = if (clean.length >= 6) {
            val a = clean.substring(0, 2)
            val b = clean.substring(2, 4)
            val c = clean.substring(4, 6)
            "https://v1.pinimg.com/videos/iht/hls/$a/$b/$c/$clean.m3u8"
        } else {
            "https://v1.pinimg.com/videos/iht/hls/$clean.m3u8"
        }
        return MediaFormat(
            label = "Video ${index + 1} · mp4 (HLS)",
            format = "mp4",
            formatId = "hls",
            url = url,
            estimatedSizeBytes = 0,
            hasAudio = true,
            httpHeaders = mapOf("Referer" to "https://www.pinterest.com/"),
        )
    }

    private fun imageFormat(label: String, url: String): MediaFormat = MediaFormat(
        label = "$label · jpg",
        format = "jpg",
        formatId = "",
        url = url,
        estimatedSizeBytes = 0,
        hasAudio = false,
        httpHeaders = mapOf("Referer" to "https://www.pinterest.com/"),
    )

    private fun gifFormat(url: String): MediaFormat = MediaFormat(
        label = "GIF · gif",
        format = "gif",
        formatId = "",
        url = url,
        estimatedSizeBytes = 0,
        hasAudio = false,
        httpHeaders = mapOf("Referer" to "https://www.pinterest.com/"),
    )

    private fun bestImage(images: JSONObject?): String? {
        if (images == null) return null
        val orig = images.optJSONObject("originals")
        if (orig != null) {
            val u = orig.optString("url")
            if (u.isNotBlank()) return u
        }
        val order = listOf("1200x", "736x", "564x", "474x", "originals", "orig")
        for (key in order) {
            val o = images.optJSONObject(key) ?: continue
            val u = o.optString("url")
            if (u.isNotBlank()) return u
        }
        val keys = images.keys().asSequence().toList()
        var best: String? = null
        var bestArea = -1L
        for (key in keys) {
            val o = images.optJSONObject(key) ?: continue
            val u = o.optString("url")
            if (u.isBlank()) continue
            val w = o.optInt("width", 0).toLong()
            val h = o.optInt("height", 0).toLong()
            val area = w * h
            if (area > bestArea) {
                bestArea = area
                best = u
            }
        }
        return best
    }
}
