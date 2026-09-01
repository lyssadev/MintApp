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
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object InstagramResolver : Resolver {

    private const val TAG = "InstagramResolver"

    private const val APP_ID = "936619743392459"
    private const val ENCODING_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val SHORTCODE_RE = Regex("(?:p|tv|reel|reels)/([A-Za-z0-9_-]+)")

    @Volatile private var appContext: Context? = null

    private val cookies = ConcurrentHashMap<String, MutableList<Cookie>>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: okhttp3.HttpUrl, list: List<Cookie>) {
                cookies.getOrPut(url.host) { mutableListOf() }.addAll(list)
            }

            override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> {
                val host = url.host
                val jarCookies = cookies[host] ?: emptyList()
                if (host.contains("instagram.com")) {
                    val saved = sessionCookies()
                    if (saved.isNotEmpty()) {
                        val names = jarCookies.map { it.name }.toSet()
                        return saved.filterNot { it.name in names } + jarCookies
                    }
                }
                return jarCookies
            }
        })
        .build()

    private val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private fun sessionCookies(): List<Cookie> {
        val ctx = appContext ?: return emptyList()
        return ConnectionPreferences.instagramCookies(ctx).mapNotNull { (name, value) ->
            runCatching {
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain("instagram.com")
                    .path("/")
                    .httpOnly()
                    .secure()
                    .build()
            }.getOrNull()
        }
    }

    fun clearSession() {
        cookies.clear()
    }

    override fun initialize(context: Context) {
        appContext = context.applicationContext
        seedSessionCookies()
    }

    private fun seedSessionCookies() {
        val saved = sessionCookies()
        if (saved.isEmpty()) return
        val list = cookies.getOrPut("www.instagram.com") { mutableListOf() }
        val names = list.map { it.name }.toSet()
        saved.filterNot { it.name in names }.forEach { list.add(it) }
    }

    override fun supports(url: String): Boolean =
        url.contains("instagram.com") && SHORTCODE_RE.containsMatchIn(url)

    override suspend fun resolve(url: String): MediaItem = withContext(Dispatchers.IO) {
        Logger.d(TAG, "resolve: url=$url")
        val shortcode = SHORTCODE_RE.find(url)?.groupValues?.get(1)
            ?: throw Exception("Could not extract Instagram shortcode")
        Logger.d(TAG, "resolve: shortcode=$shortcode")

        val product = restMedia(shortcode)
        if (product == null) {
            Logger.w(TAG, "resolve: media info request failed for $shortcode")
            val hasSession = appContext?.let { ConnectionPreferences.isInstagramLinked(it) } == true
            if (!hasSession) throw LoginRequiredException(
                "Instagram login required. Open Settings → Connections to link your account.",
            )
            throw Exception("Instagram post is not accessible without login")
        }
        Logger.d(TAG, "resolve: media info received for $shortcode")
        buildItem(url, product)
    }

    private fun restMedia(shortcode: String): JSONObject? {
        return try {
            val mediaId = shortcodeToPk(shortcode).toString()
            Logger.d(TAG, "restMedia: mediaId=$mediaId")
            val csrfToken = generateCsrf()
            cookies.getOrPut("www.instagram.com") { mutableListOf() }
                .add(Cookie.Builder()
                    .name("csrftoken")
                    .value(csrfToken)
                    .domain("instagram.com")
                    .path("/")
                    .secure()
                    .build())
            val request = Request.Builder()
                .url("https://www.instagram.com/api/v1/media/$mediaId/info/")
                .header("User-Agent", ua)
                .header("Accept", "*/*")
                .header("Origin", "https://www.instagram.com")
                .header("X-IG-App-ID", APP_ID)
                .header("X-ASBD-ID", "129477")
                .header("X-IG-WWW-Claim", "0")
                .header("X-CSRFToken", csrfToken)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Connection", "keep-alive")
                .header("Referer", "https://www.instagram.com/")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: return null
                if (!resp.isSuccessful) {
                    Logger.w(TAG, "restMedia: HTTP ${resp.code} for media $mediaId")
                    return null
                }
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
                val item = json.optJSONArray("items")?.optJSONObject(0)
                if (item == null) Logger.w(TAG, "restMedia: no items in response for $mediaId")
                item
            }
        } catch (e: Exception) {
            Logger.w(TAG, "restMedia: exception for shortcode=$shortcode", e)
            null
        }
    }

    private fun buildItem(url: String, item: JSONObject): MediaItem {
        val username = item.optJSONObject("user")?.optString("username") ?: "instagram"
        val title = item.optJSONObject("caption")?.optString("text")?.take(120) ?: "Instagram post"
        val thumbnail = bestImageUrl(item)

        val options = mutableListOf<MediaFormat>()
        val carousel = item.optJSONArray("carousel_media")
        if (carousel != null && carousel.length() > 0) {
            for (i in 0 until carousel.length()) {
                options += mediaOptions(carousel.optJSONObject(i), i)
            }
        } else {
            options += mediaOptions(item, 0)
        }

        val videos = options.filter { it.format == "mp4" }
        val images = options.filter { it.format != "mp4" }
        val isMusicOnly = videos.isEmpty() && images.isEmpty()
        Logger.d(TAG, "buildItem: ${videos.size} videos, ${images.size} images")

        return MediaItem(
            originalUrl = url,
            title = title,
            uploader = username,
            thumbnailUrl = thumbnail,
            durationText = "",
            isMusicOnly = isMusicOnly,
            streamType = if (videos.isNotEmpty()) "VIDEO" else "IMAGE",
            platform = "instagram",
            videoOptions = videos,
            audioOptions = emptyList(),
            imageOptions = images,
            gifOptions = emptyList(),
        )
    }

    private fun mediaOptions(item: JSONObject, index: Int): List<MediaFormat> {
        val out = mutableListOf<MediaFormat>()
        bestVideoUrl(item)?.let { videoUrl ->
            out += MediaFormat(
                label = "Video ${index + 1} · mp4",
                format = "mp4",
                formatId = "",
                url = videoUrl,
                estimatedSizeBytes = 0,
                hasAudio = item.optBoolean("has_audio", true),
                httpHeaders = emptyMap(),
            )
            return out
        }
        bestImageUrl(item)?.let { imageUrl ->
            out += MediaFormat(
                label = "Image ${index + 1} · jpg",
                format = "jpg",
                formatId = "",
                url = imageUrl,
                estimatedSizeBytes = 0,
                hasAudio = false,
                httpHeaders = emptyMap(),
            )
        }
        return out
    }

    private fun bestImageUrl(item: JSONObject): String? {
        val candidates = item.optJSONObject("image_versions2")?.optJSONArray("candidates") ?: return null
        return bestCandidate(candidates)
    }

    private fun bestVideoUrl(item: JSONObject): String? {
        val versions = item.optJSONArray("video_versions")
        if (versions != null && versions.length() > 0) return bestCandidate(versions)
        val dash = item.optString("video_dash_manifest")
        return if (dash.isNotBlank()) dash else null
    }

    private fun bestCandidate(arr: JSONArray): String? {
        var best: String? = null
        var bestArea = -1L
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val url = obj.optString("url")
            if (url.isBlank()) continue
            val w = obj.optInt("width", 0)
            val h = obj.optInt("height", 0)
            val area = w.toLong() * h
            if (area > bestArea) {
                bestArea = area
                best = url
            }
        }
        return best
    }

    private fun generateCsrf(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun shortcodeToPk(shortcode: String): Long {
        var code = shortcode
        if (code.length > 28) code = code.substring(0, code.length - 28)
        var num = 0L
        for (c in code) {
            val i = ENCODING_CHARS.indexOf(c)
            if (i < 0) continue
            num = num * 64 + i
        }
        return num
    }
}
