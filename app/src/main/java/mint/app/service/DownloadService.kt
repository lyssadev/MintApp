package mint.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mint.app.core.manager.DownloadManager
import mint.app.core.model.DownloadStatus
import mint.app.core.notify.NotificationHelper
import mint.app.core.storage.FileSaver
import mint.app.core.util.Logger
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val processIds = ConcurrentHashMap<String, String>()
    private val canceled = ConcurrentHashMap<String, Boolean>()
    private val titles = ConcurrentHashMap<String, String>()
    private val thumbnails = ConcurrentHashMap<String, String?>()
    @Volatile private var startedForeground = false
    @Volatile private var foregroundId = 0
    @Volatile private var activeCount = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val downloadId = intent?.getStringExtra(EXTRA_DOWNLOAD_ID)
            ?: run { stopSelf(); return START_NOT_STICKY }
        if (jobs.containsKey(downloadId)) return START_NOT_STICKY

        val originalUrl = intent.getStringExtra(EXTRA_ORIGINAL_URL) ?: run { stopSelf(); return START_NOT_STICKY }
        val formatId = intent.getStringExtra(EXTRA_FORMAT_ID) ?: run { stopSelf(); return START_NOT_STICKY }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Download"
        val format = intent.getStringExtra(EXTRA_FORMAT) ?: "mp4"
        val estimatedSize = intent.getLongExtra(EXTRA_ESTIMATED_SIZE, 0L)
        val hasAudio = intent.getBooleanExtra(EXTRA_HAS_AUDIO, true)
        val thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL)
        val imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL)
        val httpHeaders = stringMapFrom(intent.getStringExtra(EXTRA_HTTP_HEADERS))

        titles[downloadId] = title
        thumbnails[downloadId] = thumbnailUrl

        if (!startedForeground) {
            startedForeground = true
            foregroundId = NotificationHelper.idFor(downloadId)
            NotificationHelper.createChannel(this)
            startForegroundCompat(
                foregroundId,
                NotificationHelper.buildDownloading(
                    this,
                    title,
                    if (imageUrl != null) -1 else 0,
                    thumbnailUrl,
                ),
            )
        }
        activeCount++
        DownloadManager.addActive(downloadId, title, thumbnailUrl)

        val job = scope.launch {
            try {
                Logger.d(TAG, "start: $downloadId url=$originalUrl format=$formatId title=$title direct=${imageUrl != null}")
                download(
                    downloadId, originalUrl, formatId, title, format,
                    estimatedSize, hasAudio, thumbnailUrl, imageUrl, httpHeaders,
                )
            } catch (e: YoutubeDL.CanceledException) {
                Logger.d(TAG, "start: download cancelled $downloadId", e)
                NotificationHelper.dismiss(this@DownloadService, downloadId)
                DownloadManager.cancel(downloadId)
            } catch (e: InterruptedException) {
                Logger.d(TAG, "start: download interrupted $downloadId", e)
                NotificationHelper.dismiss(this@DownloadService, downloadId)
                DownloadManager.cancel(downloadId)
            } catch (e: Exception) {
                processIds.remove(downloadId)?.let { YoutubeDL.getInstance().destroyProcessById(it) }
                Logger.e(TAG, "start: download failed $downloadId: ${e.message}", e)
                DownloadManager.fail(downloadId, extractError(e.message))
            } finally {
                jobs.remove(downloadId)
                processIds.remove(downloadId)
                titles.remove(downloadId)
                thumbnails.remove(downloadId)
                canceled.remove(downloadId)
                activeCount--
                if (activeCount <= 0) {
                    startedForeground = false
                    foregroundId = 0
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (NotificationHelper.idFor(downloadId) == foregroundId) {
                    val next = jobs.keys.firstOrNull()
                    if (next != null) {
                        foregroundId = NotificationHelper.idFor(next)
                        startForegroundCompat(
                            foregroundId,
                            NotificationHelper.buildDownloading(
                                this@DownloadService,
                                titles[next] ?: "Download",
                                0,
                                thumbnails[next],
                            ),
                        )
                    }
                }
            }
        }
        jobs[downloadId] = job
        return START_NOT_STICKY
    }

    private suspend fun download(
        downloadId: String,
        originalUrl: String,
        formatId: String,
        title: String,
        format: String,
        estimatedSize: Long,
        hasAudio: Boolean,
        thumbnailUrl: String?,
        imageUrl: String?,
        httpHeaders: Map<String, String>,
    ): Unit = withContext(Dispatchers.IO) {
        val baseName = buildBaseName(title)
        val tempBase = "$baseName.${downloadId.take(8)}"
        val tempDir = File(cacheDir, "downloads")
        tempDir.mkdirs()
        val actualFile: File

        try {
            if (imageUrl != null) {
                Logger.d(TAG, "download: direct url=$imageUrl headers=${httpHeaders.size}")
                val ext = safeExtension(imageUrl, format)
                val imgFile = File(tempDir, "$tempBase.$ext")
                val url = java.net.URL(imageUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = true
                httpHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (!httpHeaders.containsKey("User-Agent")) {
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                }
                if (!httpHeaders.containsKey("Accept")) {
                    conn.setRequestProperty("Accept", "*/*")
                }
                when {
                    originalUrl.contains("tiktok.com") || originalUrl.contains("tiktokv.com") -> {
                        if (!httpHeaders.containsKey("Referer")) {
                            conn.setRequestProperty("Referer", "https://www.tiktok.com/")
                        }
                        if (!httpHeaders.containsKey("Origin")) {
                            conn.setRequestProperty("Origin", "https://www.tiktok.com")
                        }
                    }
                    originalUrl.contains("instagram.com") -> {
                        if (!httpHeaders.containsKey("Referer")) {
                            conn.setRequestProperty("Referer", "https://www.instagram.com/")
                        }
                        if (!httpHeaders.containsKey("Origin")) {
                            conn.setRequestProperty("Origin", "https://www.instagram.com")
                        }
                    }
                }
                conn.connect()
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw Exception("Download failed: HTTP $code")
                }
                val total = conn.contentLengthLong.coerceAtLeast(0)
                conn.inputStream.use { input ->
                    imgFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            if (canceled[downloadId] == true) throw YoutubeDL.CanceledException()
                            val read = input.read(buf)
                            if (read < 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            val p = if (total > 0) ((downloaded * 100) / total).toInt() else -1
                            DownloadManager.updateProgress(downloadId, if (p < 0) 0 else p, downloaded, total, 0)
                            NotificationHelper.updateProgress(this@DownloadService, downloadId, p, title, thumbnailUrl)
                        }
                    }
                }
                actualFile = imgFile
                Logger.d(TAG, "download: direct download done bytes=${imgFile.length()} total=$total")
            } else if (originalUrl.contains("tiktok.com") || originalUrl.contains("tiktokv.com")) {
                throw Exception("TikTok video URL missing from resolved data")
            } else {
                tempDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith(tempBase) }
                    ?.forEach { it.delete() }
                val formatSelector = if (formatId.isNotBlank()) {
                    if (!hasAudio) "$formatId+bestaudio[ext=m4a]" else formatId
                } else {
                    ""
                }
                val request = YoutubeDLRequest(originalUrl)
                if (formatSelector.isNotBlank()) {
                    request.addOption("-f", formatSelector)
                }
                request.addOption("-o", File(tempDir, tempBase).absolutePath)
                request.addOption("--no-mtime")
                request.addOption("--no-playlist")
                request.addOption("--merge-output-format", "mp4")
                request.addOption("--throttled-rate", "100K")
                request.addOption("--hls-prefer-ffmpeg")
                request.addOption("--retries", "10")
                request.addOption("--fragment-retries", "10")

                val processId = "mint_$downloadId"
                processIds[downloadId] = processId

                var lastBytes = 0L
                var lastTime = 0L
                var destFile: String? = null
                val callback: (Float, Long, String?) -> Unit = { progress, eta, line ->
                    if (line != null) {
                        val destMatch = Regex("""\[download\] Destination:\s+(.+)""").find(line)
                        if (destMatch != null) destFile = destMatch.groupValues[1].trim()
                        val mergeMatch = Regex("""\[Merger\] Merging formats into\s+"(.+)"""").find(line)
                        if (mergeMatch != null) destFile = mergeMatch.groupValues[1].trim()
                    }
                    val isProcessing = line != null && (line.contains("Merger", true) ||
                        line.contains("ffmpeg", true) ||
                        line.contains("Converting", true) ||
                        line.contains("Deleting", true) ||
                        line.contains("Moving", true) ||
                        line.contains("Fixup", true) ||
                        line.contains("Embedding", true))
                    if (isProcessing) {
                        DownloadManager.updatePhase(downloadId, DownloadStatus.PROCESSING)
                        NotificationHelper.notifyProcessing(this@DownloadService, downloadId, title, thumbnailUrl)
                    } else {
                        val p = progress.toFloat().coerceIn(0f, 100f)
                        val percent = p.toInt()
                        val downloaded = (p / 100.0 * estimatedSize).toLong().coerceAtLeast(0)
                        val now = System.currentTimeMillis()
                        val speed = if (lastTime > 0 && now > lastTime && downloaded >= lastBytes) {
                            ((downloaded - lastBytes) * 1000 / (now - lastTime))
                        } else {
                            0L
                        }
                        lastBytes = downloaded
                        lastTime = now
                        Logger.d(TAG, "progress[$downloadId]: $percent% | eta: ${eta}s")
                        DownloadManager.updateProgress(downloadId, percent, downloaded, estimatedSize, speed)
                        NotificationHelper.updateProgress(this@DownloadService, downloadId, percent, title, thumbnailUrl)
                    }
                }

                Logger.d(TAG, "download: yt-dlp formatSelector='$formatSelector'")
                YoutubeDL.getInstance().execute(request, processId, callback)

                val dest = destFile?.let { File(it) }?.takeIf { it.isFile }
                val fallback = tempDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith(tempBase) && !it.name.endsWith(".part") }
                    ?.filterNot { it.name.contains(Regex("\\.f\\d+")) }
                    ?.maxByOrNull { it.lastModified() }
                actualFile = dest ?: fallback
                    ?: throw Exception("downloaded file not found")
                Logger.d(TAG, "download: yt-dlp done file=${actualFile.name} size=${actualFile.length()}")
            }

            val rawExt = actualFile.name.substringAfterLast('.', "").ifBlank { format }
            val actualExt = rawExt.takeIf { it in KNOWN_EXTENSIONS } ?: format
            val finalName = "$baseName.$actualExt"
            val finalMime = mimeFor(actualExt)
            val isAudio = finalMime.startsWith("audio/")
            val isImage = finalMime.startsWith("image/")

            val target = FileSaver.openForWrite(this@DownloadService, finalName, finalMime, isAudio, isImage)
            try {
                actualFile.inputStream().use { input ->
                    target.outputStream.use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "download: file copy error for $downloadId", e)
                target.uri?.let { uri ->
                    runCatching { contentResolver.delete(uri, null, null) }
                }
                throw e
            }
            val sizeBytes = actualFile.length()
            actualFile.delete()

            DownloadManager.complete(downloadId, finalName, target.displayPath, target.uri?.toString(), finalMime, sizeBytes)
            Logger.d(TAG, "download: completed $downloadId name=$finalName size=$sizeBytes")
            vibrate()
            NotificationHelper.dismiss(this@DownloadService, downloadId)
            val completed = DownloadManager.items.value.filter { it.status == DownloadStatus.COMPLETED }
            NotificationHelper.notifyCompletedList(this@DownloadService, completed)
        } catch (e: Exception) {
            tempDir.listFiles()?.filter { it.name.startsWith(tempBase) }?.forEach { it.delete() }
            throw e
        }
    }

    private fun startForegroundCompat(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                id,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(id, notification)
        }
    }

    private fun vibrate() {
        val pattern = longArrayOf(0, 80, 60, 80)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibrator = (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(pattern, -1)
        }
    }

    override fun onDestroy() {
        instance = null
        processIds.values.forEach { YoutubeDL.getInstance().destroyProcessById(it) }
        scope.cancel()
        super.onDestroy()
    }

    private fun extractError(raw: String?): String {
        if (raw.isNullOrBlank()) return "Download failed"
        val lines = raw.lines()
        val interesting = lines.filter { line ->
            line.contains("ERROR", ignoreCase = true) ||
                line.contains("Error", ignoreCase = true) ||
                line.contains("Traceback", ignoreCase = true) ||
                line.contains("ENOENT", ignoreCase = true) ||
                line.contains("No such file", ignoreCase = true) ||
                line.contains("ffmpeg", ignoreCase = true)
        }
        return if (interesting.isNotEmpty()) {
            interesting.takeLast(6).joinToString("\n").trim()
        } else {
            lines.takeLast(3).joinToString("\n").trim()
        }
    }

    private fun safeExtension(url: String, fallback: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.').lowercase()
        return if (ext.isNotBlank() && ext.length <= 8 && ext.all { it.isLetterOrDigit() } && ext in KNOWN_EXTENSIONS) ext else fallback
    }

    private fun buildBaseName(title: String): String {
        var result = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (result.codePointCount(0, result.length) > 80) {
            val end = result.offsetByCodePoints(0, 80)
            result = result.substring(0, end)
        }
        val clean = StringBuilder(result.length)
        var i = 0
        while (i < result.length) {
            val cp = result.codePointAt(i)
            if (cp in 0xD800..0xDFFF) {
                i++
                continue
            }
            clean.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return clean.toString().trim().ifBlank { "download" }
    }

    private fun mimeFor(format: String): String = when (format.lowercase()) {
        "mp4" -> "video/mp4"
        "m4a" -> "audio/mp4"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "opus", "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "mkv" -> "video/x-matroska"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "MintDownload"

        private val KNOWN_EXTENSIONS = setOf(
            "mp4", "m4a", "webm", "3gp", "mp3", "opus", "ogg", "flac", "wav",
            "mkv", "jpg", "jpeg", "png", "gif", "webp", "heic",
        )
        private const val EXTRA_DOWNLOAD_ID = "download_id"
        private const val EXTRA_ORIGINAL_URL = "original_url"
        private const val EXTRA_FORMAT_ID = "format_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FORMAT = "format"
        private const val EXTRA_ESTIMATED_SIZE = "estimated_size"
        private const val EXTRA_HAS_AUDIO = "has_audio"
        private const val EXTRA_THUMBNAIL = "thumbnail"
        private const val EXTRA_IMAGE_URL = "image_url"
        private const val EXTRA_HTTP_HEADERS = "http_headers"
        const val EXTRA_CANCEL_ID = "cancel_id"

        @Volatile private var instance: DownloadService? = null

        fun start(
            context: Context,
            originalUrl: String,
            formatId: String,
            title: String,
            format: String,
            estimatedSize: Long = 0,
            hasAudio: Boolean = true,
            thumbnail: String? = null,
            imageUrl: String? = null,
            httpHeaders: Map<String, String> = emptyMap(),
        ): String {
            val downloadId = UUID.randomUUID().toString()
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                putExtra(EXTRA_ORIGINAL_URL, originalUrl)
                putExtra(EXTRA_FORMAT_ID, formatId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_FORMAT, format)
                putExtra(EXTRA_ESTIMATED_SIZE, estimatedSize)
                putExtra(EXTRA_HAS_AUDIO, hasAudio)
                putExtra(EXTRA_THUMBNAIL, thumbnail)
                putExtra(EXTRA_IMAGE_URL, imageUrl)
                putExtra(EXTRA_HTTP_HEADERS, stringMapTo(httpHeaders))
            }
            ContextCompat.startForegroundService(context, intent)
            return downloadId
        }

        fun cancel(id: String) {
            instance?.canceled?.set(id, true)
            instance?.processIds?.remove(id)?.let { YoutubeDL.getInstance().destroyProcessById(it) }
        }

        fun cancelBroadcast(context: Context, id: String) {
            val intent = Intent(context, CancelDownloadReceiver::class.java).apply {
                putExtra(EXTRA_CANCEL_ID, id)
            }
            context.sendBroadcast(intent)
        }

        private fun stringMapTo(map: Map<String, String>): String? {
            if (map.isEmpty()) return null
            return map.entries.joinToString("\u0001") { "${it.key}\u0002${it.value}" }
        }

        private fun stringMapFrom(raw: String?): Map<String, String> {
            if (raw.isNullOrEmpty()) return emptyMap()
            return raw.split("\u0001").mapNotNull { entry ->
                val idx = entry.indexOf('\u0002')
                if (idx <= 0) null else entry.substring(0, idx) to entry.substring(idx + 1)
            }.toMap()
        }
    }
}