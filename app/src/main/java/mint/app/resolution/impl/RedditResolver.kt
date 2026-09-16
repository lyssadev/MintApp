package mint.app.resolution.impl

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mint.app.core.model.MediaFormat
import mint.app.core.model.MediaItem
import mint.app.core.util.Logger
import mint.app.resolution.Resolver
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

object RedditResolver : Resolver {

	private const val TAG = "RedditResolver"

	private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
	private const val PAGE_BASE = "https://www.reddit.com"
	private const val EMBED_BASE = "https://embed.reddit.com"
	private const val IMAGE_BASE = "https://i.redd.it"
	private const val VIDEO_BASE = "https://v.redd.it"

	private val POST_RE = Regex("""/comments/([A-Za-z0-9]+)""")
	private val SUB_RE = Regex("""/r/([A-Za-z0-9_]+)/""")
	private val SHARE_RE = Regex("""/s/[A-Za-z0-9]+""")
	private val POST_TYPE_RE = Regex("post-type=\"([a-z]+)\"")
	private val TITLE_RE = Regex("post-title=\"([^\"]*)\"")
	private val AUTHOR_RE = Regex("\\sauthor=\"([^\"]*)\"")
	private val PACKAGED_RE = Regex("packaged-media-json=\"([^\"]*)\"")
	private val POSTER_RE = Regex("\\sposter=\"([^\"]+)\"")
	private val CONTENT_HREF_RE = Regex("content-href=\"([^\"]+)\"")
	private val VIDEO_ID_RE = Regex("""https?://v\.redd\.it/([A-Za-z0-9]+)""")
	private val IMAGE_HREF_RE = Regex("""https?://i\.redd\.it/([A-Za-z0-9]+)\.([A-Za-z0-9]+)""")
	private val H1_RE = Regex("""<h1[^>]*>([^<>]{1,300})</h1>""", RegexOption.IGNORE_CASE)
	private val USER_RE = Regex("""reddit\.com/user/([A-Za-z0-9_-]{1,32})""", RegexOption.IGNORE_CASE)
	private val HLS_ID_RE = Regex("""https://v\.redd\.it/([A-Za-z0-9]+)/HLSPlaylist\.m3u8""")
	private val IMG_SRC_RE = Regex("""<img[^>]*\ssrc="([^"]+)"""", RegexOption.IGNORE_CASE)
	private val HLS_ATTR_RE = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
	private val HLS_VARIANT_RE = Regex("""#EXT-X-STREAM-INF:([^\r\n]*)\r?\n([^\r\n#]+)""")
	private val HLS_MEDIA_RE = Regex("""#EXT-X-MEDIA:([^\r\n]*)""")
	private val HLS_EXTINF_RE = Regex("""#EXTINF:([0-9.]+)""")
	private val HLS_BYTERANGE_RE = Regex("""#EXT-X-BYTERANGE:(\d+)""")
	private val HLS_MAP_BYTES_RE = Regex("""#EXT-X-MAP:[^\r\n]*BYTERANGE="(\d+)""")
	private val PREVIEW_RE = Regex(
		"""https://preview\.redd\.it/[A-Za-z0-9_-]+-v0-([A-Za-z0-9]+)\.(jpg|jpeg|png|gif|webp)""",
		RegexOption.IGNORE_CASE,
	)

	private val IMAGE_EXTS = setOf("jpg", "png", "gif", "webp")

	private val pageHeaders: Headers = Headers.Builder()
		.add("User-Agent", UA)
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
		.add("Accept-Language", "en-US,en;q=0.9")
		.add("sec-ch-ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"")
		.add("sec-ch-ua-mobile", "?1")
		.add("sec-ch-ua-platform", "\"Android\"")
		.add("sec-fetch-dest", "document")
		.add("sec-fetch-mode", "navigate")
		.add("sec-fetch-site", "none")
		.add("upgrade-insecure-requests", "1")
		.build()

	private val mediaHeaders = mapOf(
		"User-Agent" to UA,
		"Accept" to "*/*",
	)

	private val client = OkHttpClient.Builder()
		.connectTimeout(15, TimeUnit.SECONDS)
		.readTimeout(20, TimeUnit.SECONDS)
		.followRedirects(true)
		.followSslRedirects(true)
		.build()

	private data class GalleryEntry(val mediaId: String, val ext: String, val previewUrl: String)

	private data class Choice(val format: MediaFormat, val durationSeconds: Int)

	private data class HlsVariant(val url: String, val height: Int, val bitrate: Long, val audioGroup: String?)

	private data class Page(
		val title: String,
		val author: String,
		val thumbnail: String?,
		val packaged: String?,
		val mediaHref: String?,
		val gallery: List<GalleryEntry>,
		val playerVideoId: String?,
	)

	override fun initialize(context: Context) = Unit

	override fun supports(url: String): Boolean {
		val host = hostOf(url) ?: return false
		return when {
			host == "i.redd.it" -> true
			host == "v.redd.it" -> true
			host == "redd.it" -> true
			host == "reddit.com" -> POST_RE.containsMatchIn(url) || SHARE_RE.containsMatchIn(url)
			host.endsWith(".reddit.com") -> POST_RE.containsMatchIn(url) || SHARE_RE.containsMatchIn(url)
			else -> false
		}
	}

	override suspend fun resolve(url: String): MediaItem = withContext(Dispatchers.IO) {
		val input = url.trim()
		Logger.d(TAG, "resolve: url=$input")

		directMedia(input)?.let { return@withContext it }

		val postId = POST_RE.find(input)?.groupValues?.get(1)
		var subreddit = SUB_RE.find(input)?.groupValues?.get(1)
		val extractedId = postId ?: canonicalUrl(input)?.let { canonical ->
			Logger.d(TAG, "resolve: canonical=$canonical")
			subreddit = subreddit ?: SUB_RE.find(canonical)?.groupValues?.get(1)
			POST_RE.find(canonical)?.groupValues?.get(1)
		}
		val id = extractedId ?: throw Exception("Could not find a Reddit post in this link.")
		Logger.d(TAG, "resolve: postId=$id subreddit=$subreddit")

		val pageHtml = fetchPage("$PAGE_BASE/comments/$id/")
		var loaded = pageHtml != null
		var page = pageHtml?.let { parsePage(it) }
		if (page == null) {
			if (subreddit == null) {
				subreddit = canonicalUrl(input)?.let { SUB_RE.find(it)?.groupValues?.get(1) }
			}
			Logger.d(TAG, "resolve: falling back to embed, subreddit=$subreddit")
			val embedHtml = subreddit?.let { sub -> fetchPage("$EMBED_BASE/r/$sub/comments/$id/") }
			loaded = loaded || embedHtml != null
			page = embedHtml?.let { parsePage(it) }
		}
		val parsed = page ?: throw Exception(
			if (loaded) {
				"Could not find downloadable media in this Reddit post."
			} else {
				"Could not read this Reddit post. It may be private, removed or blocked on this network."
			},
		)

		Logger.d(
			TAG,
			"resolve: postId=$id packaged=${parsed.packaged != null} gallery=${parsed.gallery.size} " +
				"playerVideo=${parsed.playerVideoId}",
		)
		buildItem(input, parsed)
	}

	private fun directMedia(input: String): MediaItem? {
		val host = hostOf(input) ?: return null
		val fileName = pathSegment(input) ?: return null
		return when (host) {
			"i.redd.it" -> {
				val mediaId = fileName.substringBeforeLast('.', "")
				if (mediaId.isBlank()) return null
				val ext = normalizeExt(fileName.substringAfterLast('.', "jpg"))
				val format = imageFormat(1, mediaId, ext)
				val images = if (ext == "gif") emptyList() else listOf(format)
				val gifs = if (ext == "gif") listOf(format) else emptyList()
				MediaItem(
					originalUrl = input,
					title = "Reddit image",
					uploader = "reddit",
					thumbnailUrl = format.url,
					durationText = "",
					isMusicOnly = false,
					streamType = "IMAGE",
					platform = "reddit",
					videoOptions = emptyList(),
					audioOptions = emptyList(),
					imageOptions = images,
					gifOptions = gifs,
				)
			}
			"v.redd.it" -> {
				val mediaId = fileName.substringBefore('.', fileName)
				if (mediaId.isBlank()) return null
				MediaItem(
					originalUrl = input,
					title = "Reddit video",
					uploader = "reddit",
					thumbnailUrl = null,
					durationText = "",
					isMusicOnly = false,
					streamType = "VIDEO",
					platform = "reddit",
					videoOptions = listOf(hlsChoice(mediaId).format),
					audioOptions = emptyList(),
					imageOptions = emptyList(),
					gifOptions = emptyList(),
				)
			}
			else -> null
		}
	}

	private fun buildItem(input: String, page: Page): MediaItem {
		if (page.gallery.isNotEmpty()) {
			val images = mutableListOf<MediaFormat>()
			val gifs = mutableListOf<MediaFormat>()
			page.gallery.forEachIndexed { index, entry ->
				val format = imageFormat(index + 1, entry.mediaId, entry.ext)
				if (entry.ext == "gif") gifs += format else images += format
			}
			val videos = listOfNotNull(videoChoice(page)?.format)
			Logger.d(TAG, "buildItem: carousel images=${images.size} gifs=${gifs.size} videos=${videos.size}")
			return MediaItem(
				originalUrl = input,
				title = page.title,
				uploader = page.author,
				thumbnailUrl = page.thumbnail,
				durationText = "",
				isMusicOnly = false,
				streamType = "IMAGE",
				platform = "reddit",
				videoOptions = videos,
				audioOptions = emptyList(),
				imageOptions = images,
				gifOptions = gifs,
			)
		}

		val image = imageFromHref(page.mediaHref)
		if (image != null) {
			val format = imageFormat(1, image.first, image.second)
			Logger.d(TAG, "buildItem: single image ${image.first}.${image.second}")
			return MediaItem(
				originalUrl = input,
				title = page.title,
				uploader = page.author,
				thumbnailUrl = page.thumbnail ?: format.url,
				durationText = "",
				isMusicOnly = false,
				streamType = "IMAGE",
				platform = "reddit",
				videoOptions = emptyList(),
				audioOptions = emptyList(),
				imageOptions = listOf(format),
				gifOptions = emptyList(),
			)
		}

		val choice = videoChoice(page)
			?: throw Exception("Could not find downloadable media in this Reddit post.")
		Logger.d(TAG, "buildItem: video label=${choice.format.label} mux=${choice.format.formatId == "hls"}")
		return MediaItem(
			originalUrl = input,
			title = page.title,
			uploader = page.author,
			thumbnailUrl = page.thumbnail,
			durationText = formatDuration(choice.durationSeconds),
			isMusicOnly = false,
			streamType = "VIDEO",
			platform = "reddit",
			videoOptions = listOf(choice.format),
			audioOptions = emptyList(),
			imageOptions = emptyList(),
			gifOptions = emptyList(),
		)
	}

	private fun parsePage(html: String): Page? {
		val packaged = PACKAGED_RE.find(html)?.groupValues?.get(1)?.let { unescape(it) }
		val mediaHref = CONTENT_HREF_RE.find(html)?.groupValues?.get(1)?.let { unescape(it) }
		val gallery = galleryEntries(html)
		val playerVideoId = HLS_ID_RE.find(html)?.groupValues?.get(1)
		val postType = POST_TYPE_RE.find(html)?.groupValues?.get(1)
		Logger.d(
			TAG,
			"parsePage: postType=$postType packaged=${packaged != null} gallery=${gallery.size} " +
				"playerVideo=$playerVideoId href=$mediaHref",
		)
		if (packaged == null && gallery.isEmpty() && mediaHref == null && playerVideoId == null) {
			return null
		}

		val title = TITLE_RE.find(html)?.groupValues?.get(1)?.let { unescape(it) }
			?.takeIf { it.isNotBlank() }
			?: H1_RE.find(html)?.groupValues?.get(1)?.let { unescape(it) }?.takeIf { it.isNotBlank() }
			?: "Reddit post"
		val author = AUTHOR_RE.find(html)?.groupValues?.get(1)?.let { unescape(it) }
			?.takeIf { it.isNotBlank() }
			?: USER_RE.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
			?: "reddit"
		val poster = POSTER_RE.find(html)?.groupValues?.get(1)?.let { unescape(it) }

		return Page(
			title = title,
			author = author,
			thumbnail = poster ?: gallery.firstOrNull()?.previewUrl,
			packaged = packaged,
			mediaHref = mediaHref,
			gallery = gallery,
			playerVideoId = playerVideoId,
		)
	}

	private fun galleryEntries(html: String): List<GalleryEntry> {
		val seen = mutableSetOf<String>()
		val entries = mutableListOf<Pair<Int, GalleryEntry>>()
		PREVIEW_RE.findAll(html).forEach { match ->
			val mediaId = match.groupValues[1]
			val ext = normalizeExt(match.groupValues[2])
			if (seen.add("$mediaId.$ext")) {
				entries += match.range.first to GalleryEntry(mediaId, ext, match.value)
			}
		}
		IMG_SRC_RE.findAll(html).forEach { match ->
			val href = unescape(match.groupValues[1])
			val image = imageFromHref(href) ?: return@forEach
			if (seen.add("${image.first}.${image.second}")) {
				entries += match.range.first to GalleryEntry(image.first, image.second, href)
			}
		}
		return entries.sortedBy { it.first }.map { it.second }
	}

	private fun videoChoice(page: Page): Choice? {
		packagedChoice(page.packaged)?.let { return it }
		val mediaId = page.mediaHref?.let { VIDEO_ID_RE.find(it)?.groupValues?.get(1) }
			?: page.playerVideoId
			?: return null
		return hlsChoice(mediaId)
	}

	private fun packagedChoice(packaged: String?): Choice? {
		val json = packaged?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
		val playback = json.optJSONObject("playbackMp4s") ?: json
		val permutations = playback.optJSONArray("permutations") ?: return null
		var bestUrl: String? = null
		var bestHeight = 0
		var bestArea = -1L
		for (index in 0 until permutations.length()) {
			val source = permutations.optJSONObject(index)?.optJSONObject("source") ?: continue
			val candidate = source.optString("url").takeIf { it.isNotBlank() } ?: continue
			val dimensions = source.optJSONObject("dimensions")
			val width = dimensions?.optInt("width", 0) ?: 0
			val height = dimensions?.optInt("height", 0) ?: 0
			val area = width.toLong() * height.toLong()
			if (area > bestArea) {
				bestArea = area
				bestHeight = height
				bestUrl = candidate
			}
		}
		val url = bestUrl ?: return null
		return Choice(
			format = MediaFormat(
				label = if (bestHeight > 0) "${bestHeight}p · mp4" else "Best · mp4",
				format = "mp4",
				formatId = if (bestHeight > 0) "m2-res_${bestHeight}p" else "m2-res",
				url = url,
				estimatedSizeBytes = 0,
				hasAudio = true,
				httpHeaders = mediaHeaders,
			),
			durationSeconds = playback.optInt("duration", 0),
		)
	}

	private fun hlsChoice(mediaId: String): Choice {
		val url = "$VIDEO_BASE/$mediaId/HLSPlaylist.m3u8"
		val master = fetchPage(url)
		val variants = master?.let { hlsVariants(url, it) }.orEmpty()
		val variant = variants.maxWithOrNull(compareBy({ it.height }, { it.bitrate }))
		val playlist = variant?.let { fetchPage(it.url) } ?: master
		val durationSeconds = playlist?.let { playlistDurationSeconds(it) } ?: 0
		var sizeBytes = playlist?.let { playlistBytes(it) } ?: 0L
		if (master != null && variant?.audioGroup != null) {
			sizeBytes += hlsAudioUrl(url, master, variant.audioGroup)
				?.let { fetchPage(it) }
				?.let { playlistBytes(it) }
				?: 0L
		}
		if (sizeBytes <= 0L && variant != null && durationSeconds > 0) {
			sizeBytes = variant.bitrate / 8L * durationSeconds
		}
		Logger.d(
			TAG,
			"hlsChoice: mediaId=$mediaId variants=${variants.size} duration=${durationSeconds}s size=$sizeBytes",
		)
		return Choice(
			format = MediaFormat(
				label = "Best · mp4",
				format = "mp4",
				formatId = "hls",
				url = url,
				estimatedSizeBytes = sizeBytes,
				hasAudio = true,
				httpHeaders = mediaHeaders,
			),
			durationSeconds = durationSeconds,
		)
	}

	private fun hlsVariants(playlistUrl: String, master: String): List<HlsVariant> {
		val base = playlistUrl.substringBeforeLast('/')
		return HLS_VARIANT_RE.findAll(master).mapNotNull { match ->
			val uri = match.groupValues[2].trim()
			if (uri.isBlank()) return@mapNotNull null
			val attrs = hlsAttrs(match.groupValues[1])
			HlsVariant(
				url = absoluteUrl(base, uri),
				height = attrs["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: 0,
				bitrate = (attrs["AVERAGE-BANDWIDTH"] ?: attrs["BANDWIDTH"])?.toLongOrNull() ?: 0L,
				audioGroup = attrs["AUDIO"],
			)
		}.toList()
	}

	private fun hlsAudioUrl(playlistUrl: String, master: String, group: String): String? =
		HLS_MEDIA_RE.findAll(master)
			.map { hlsAttrs(it.groupValues[1]) }
			.firstOrNull { it["TYPE"] == "AUDIO" && it["GROUP-ID"] == group }
			?.get("URI")
			?.let { absoluteUrl(playlistUrl.substringBeforeLast('/'), it) }

	private fun hlsAttrs(tag: String): Map<String, String> =
		HLS_ATTR_RE.findAll(tag).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

	private fun absoluteUrl(base: String, uri: String): String =
		if (uri.startsWith("http")) uri else "$base/${uri.trimStart('/')}"

	private fun playlistDurationSeconds(playlist: String): Int =
		HLS_EXTINF_RE.findAll(playlist).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }.toInt()

	private fun playlistBytes(playlist: String): Long {
		var total = 0L
		HLS_BYTERANGE_RE.findAll(playlist).forEach { total += it.groupValues[1].toLongOrNull() ?: 0L }
		HLS_MAP_BYTES_RE.findAll(playlist).forEach { total += it.groupValues[1].toLongOrNull() ?: 0L }
		return total
	}

	private fun imageFromHref(href: String?): Pair<String, String>? {
		val match = href?.let { IMAGE_HREF_RE.find(it) } ?: return null
		return match.groupValues[1] to normalizeExt(match.groupValues[2])
	}

	private fun imageFormat(index: Int, mediaId: String, ext: String): MediaFormat = MediaFormat(
		label = if (ext == "gif") "GIF $index · gif" else "Image $index · $ext",
		format = ext,
		formatId = mediaId,
		url = "$IMAGE_BASE/$mediaId.$ext",
		estimatedSizeBytes = 0,
		hasAudio = false,
		httpHeaders = mediaHeaders,
	)

	private fun normalizeExt(raw: String): String {
		val ext = raw.lowercase()
		return when {
			ext == "jpeg" -> "jpg"
			ext in IMAGE_EXTS -> ext
			else -> "jpg"
		}
	}

	private fun fetchPage(url: String): String? = try {
		val request = Request.Builder().url(url).headers(pageHeaders).build()
		client.newCall(request).execute().use { response ->
			val body = response.body?.string().orEmpty()
			Logger.d(TAG, "fetchPage: url=$url status=${response.code} bytes=${body.length}")
			if (response.isSuccessful) body.takeIf { it.isNotBlank() } else null
		}
	} catch (e: Exception) {
		Logger.w(TAG, "fetchPage: failed url=$url", e)
		null
	}

	private fun canonicalUrl(url: String): String? = try {
		val request = Request.Builder().url(url).headers(pageHeaders).build()
		client.newCall(request).execute().use { response ->
			val finalUrl = response.request.url.toString()
			Logger.d(TAG, "canonicalUrl: url=$url status=${response.code} final=$finalUrl")
			if (response.isSuccessful) finalUrl else null
		}
	} catch (e: Exception) {
		Logger.w(TAG, "canonicalUrl: failed url=$url", e)
		null
	}

	private fun unescape(value: String): String = value
		.replace("&quot;", "\"")
		.replace("&#39;", "'")
		.replace("&#x27;", "'")
		.replace("&lt;", "<")
		.replace("&gt;", ">")
		.replace("&amp;", "&")

	private fun formatDuration(seconds: Int): String {
		if (seconds <= 0) return ""
		val hours = seconds / 3600
		val minutes = (seconds % 3600) / 60
		val secs = seconds % 60
		return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
	}

	private fun hostOf(url: String): String? =
		runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()

	private fun pathSegment(url: String): String? = runCatching {
		URI(url.trim()).path?.trim('/')?.substringBefore('/')?.takeIf { it.isNotBlank() }
	}.getOrNull()
}
