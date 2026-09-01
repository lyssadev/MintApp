package mint.app.resolution.impl

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mint.app.core.model.MediaFormat
import mint.app.core.model.MediaItem
import mint.app.resolution.Resolver
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object InstagramResolver : Resolver {

    private const val TAG = "MintInit"
    private const val APP_ID = "936619743392459"
    private const val GRAPHQL_DOC_ID = "27130156389949648"
    private const val GRAPHQL_URL = "https://www.instagram.com/api/graphql"
    private const val ENCODING_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val SHORTCODE_RE = Regex("(?:p|tv|reel|reels)/([A-Za-z0-9_-]+)")
    private val SJS_RE = Regex("<script\\b[^>]*\\bdata-sjs>(\\{.+?\\})</script>", setOf(RegexOption.DOT_MATCHES_ALL))

    private val cookies = ConcurrentHashMap<String, MutableList<Cookie>>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: okhttp3.HttpUrl, list: List<Cookie>) {
                cookies.getOrPut(url.host) { mutableListOf() }.addAll(list)
            }

            override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> =
                cookies[url.host] ?: emptyList()
        })
        .build()

    private val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    override fun initialize(context: Context) {
        // no native init needed
    }

    override fun supports(url: String): Boolean =
        url.contains("instagram.com") && SHORTCODE_RE.containsMatchIn(url)

    override suspend fun resolve(url: String): MediaItem = withContext(Dispatchers.IO) {
        val shortcode = SHORTCODE_RE.find(url)?.groupValues?.get(1)
            ?: throw Exception("Could not extract Instagram shortcode")
        Log.d(TAG, "ig resolve shortcode=$shortcode")

        val page = fetch("https://www.instagram.com/p/$shortcode/")
        var media = findMediaFromPage(page)
        if (media == null) {
            Log.d(TAG, "ig embedded json not found, falling back to graphql")
            media = graphqlMedia(shortcode, url)
        }
        if (media == null) throw Exception("Instagram post is not accessible without login")

        val product = media.optJSONObject("if_not_gated_logged_out")
            ?: throw Exception("Instagram post is not accessible without login")
        buildItem(url, product)
    }

    private fun buildItem(url: String, product: JSONObject): MediaItem {
        val username = product.optJSONObject("user")?.optString("username") ?: "instagram"
        val title = product.optJSONObject("caption")?.optString("text")?.take(120) ?: "Instagram post"
        val thumbnail = bestImageUrl(product)

        val options = mutableListOf<MediaFormat>()
        val carousel = product.optJSONArray("carousel_media")
        if (carousel != null && carousel.length() > 0) {
            for (i in 0 until carousel.length()) {
                options += mediaOptions(carousel.optJSONObject(i), i)
            }
        } else {
            options += mediaOptions(product, 0)
        }

        val videos = options.filter { it.format == "mp4" }
        val images = options.filter { it.format != "mp4" }
        val isMusicOnly = videos.isEmpty() && images.isEmpty()

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
        }
        Log.d(TAG, "ig media #$index -> ${out.map { it.format }}")
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

    private fun findMediaFromPage(page: String): JSONObject? {
        for (match in SJS_RE.findAll(page)) {
            val body = match.groupValues.getOrNull(1) ?: continue
            val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
            deepFind(json, "xig_polaris_media")?.let { return it }
        }
        return null
    }

    private fun deepFind(node: Any?, key: String): JSONObject? {
        if (node == null) return null
        return when (node) {
            is JSONObject -> {
                if (node.has(key)) node.optJSONObject(key) else {
                    val it = node.keys()
                    while (it.hasNext()) {
                        deepFind(node.opt(it.next()), key)?.let { return it }
                    }
                    null
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    deepFind(node.opt(i), key)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun graphqlMedia(shortcode: String, referer: String): JSONObject? {
        return try {
            val mediaId = shortcodeToPk(shortcode).toString()
            val home = fetch("https://www.instagram.com/")
            val lsd = Regex("""\["LSD",\[\],\{"token":"([^"]+)""").find(home)?.groupValues?.get(1)
            val csrf = cookies["www.instagram.com"]
                ?.firstOrNull { it.name == "csrftoken" }?.value
            if (lsd == null) {
                Log.e(TAG, "ig no lsd token")
                return null
            }
            val body = FormBody.Builder()
                .add("lsd", lsd)
                .add("fb_api_caller_class", "RelayModern")
                .add("fb_api_req_friendly_name", "PolarisLoggedOutDesktopWWWPostRootContentQuery")
                .add("server_timestamps", "true")
                .add("variables", JSONObject().put("media_id", mediaId).toString())
                .add("doc_id", GRAPHQL_DOC_ID)
                .build()
            val request = Request.Builder()
                .url(GRAPHQL_URL)
                .post(body)
                .header("User-Agent", ua)
                .header("X-IG-App-ID", APP_ID)
                .header("X-ASBD-ID", "359341")
                .header("X-IG-WWW-Claim", "0")
                .header("X-FB-Friendly-Name", "PolarisLoggedOutDesktopWWWPostRootContentQuery")
                .header("X-FB-LSD", lsd)
                .header("X-CSRFToken", csrf ?: "")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", referer)
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: return null
                if (!resp.isSuccessful) {
                    Log.e(TAG, "ig graphql http ${resp.code}")
                    return null
                }
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
                deepFind(json, "xig_polaris_media")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ig graphql failed: ${e.message}")
            null
        }
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} for $url")
            return resp.body?.string() ?: throw Exception("Empty response for $url")
        }
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
