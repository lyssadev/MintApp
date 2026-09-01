package mint.app.data

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
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
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val processIds = ConcurrentHashMap<String, String>()
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

        titles[downloadId] = title
        thumbnails[downloadId] = thumbnailUrl

        if (!startedForeground) {
            startedForeground = true
            foregroundId = NotificationHelper.idFor(downloadId)
            NotificationHelper.createChannel(this)
            startForegroundCompat(
                foregroundId,
                NotificationHelper.buildDownloading(this, title, 0, thumbnailUrl),
            )
        }
        activeCount++
        DownloadManager.addActive(downloadId, title, thumbnailUrl)

        val job = scope.launch {
            try {
                download(downloadId, originalUrl, formatId, title, format, estimatedSize, hasAudio, thumbnailUrl)
            } catch (e: YoutubeDL.CanceledException) {
                Log.d(TAG, "download cancelled: $downloadId")
                NotificationHelper.dismiss(this@DownloadService, downloadId)
                DownloadManager.cancel(downloadId)
            } catch (e: InterruptedException) {
                Log.d(TAG, "download interrupted: $downloadId")
                NotificationHelper.dismiss(this@DownloadService, downloadId)
                DownloadManager.cancel(downloadId)
            } catch (e: Exception) {
                processIds.remove(downloadId)?.let { YoutubeDL.getInstance().destroyProcessById(it) }
                Log.e(TAG, "download failed: $downloadId ${e.message}")
                DownloadManager.fail(downloadId, extractError(e.message))
            } finally {
                jobs.remove(downloadId)
                processIds.remove(downloadId)
                titles.remove(downloadId)
                thumbnails.remove(downloadId)
                activeCount--
                if (activeCount <= 0) {
                    startedForeground = false
                    foregroundId = 0
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (NotificationHelper.idFor(downloadId) == foregroundId) {
                    // move foreground to another active download so it keeps a live notification
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
    ): Unit = withContext(Dispatchers.IO) {
        val baseName = buildBaseName(title)
        val tempDir = File(cacheDir, "downloads")
        tempDir.mkdirs()

        try {
            val formatSelector = if (!hasAudio) "$formatId+bestaudio[ext=m4a]" else formatId
            val request = YoutubeDLRequest(originalUrl)
            request.addOption("-f", formatSelector)
            request.addOption("-o", File(tempDir, baseName).absolutePath)
            request.addOption("--no-mtime")
            request.addOption("--no-playlist")
            request.addOption("--throttled-rate", "100K")
            request.addOption("--hls-prefer-ffmpeg")
            request.addOption("--retries", "10")
            request.addOption("--fragment-retries", "10")

            val processId = "mint_$downloadId"
            processIds[downloadId] = processId

            var lastBytes = 0L
            var lastTime = 0L
            val callback: (Float, Long, String?) -> Unit = { progress, eta, line ->
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
                    Log.d(TAG, "progress[$downloadId]: $percent% | eta: ${eta}s")
                    DownloadManager.updateProgress(downloadId, percent, downloaded, estimatedSize, speed)
                    NotificationHelper.updateProgress(this@DownloadService, downloadId, percent, title, thumbnailUrl)
                }
            }

            YoutubeDL.getInstance().execute(request, processId, callback)

            val actualFile = tempDir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(baseName) && !it.name.endsWith(".part") }
                ?.maxByOrNull { it.lastModified() }
                ?: throw Exception("downloaded file not found")

            val actualExt = actualFile.extension.ifBlank { format }
            val finalName = "$baseName.$actualExt"
            val finalMime = mimeFor(actualExt)
            val isAudio = finalMime.startsWith("audio/")

            val target = FileSaver.openForWrite(this@DownloadService, finalName, finalMime, isAudio)
            try {
                actualFile.inputStream().use { input ->
                    target.outputStream.use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
            } catch (e: Exception) {
                target.uri?.let { uri ->
                    runCatching { contentResolver.delete(uri, null, null) }
                }
                throw e
            }
            val sizeBytes = actualFile.length()
            actualFile.delete()

            DownloadManager.complete(downloadId, finalName, target.displayPath, target.uri?.toString(), finalMime, sizeBytes)
            vibrate()
            NotificationHelper.dismiss(this@DownloadService, downloadId)
            val completed = DownloadManager.items.value.filter { it.status == DownloadStatus.COMPLETED }
            NotificationHelper.notifyCompletedList(this@DownloadService, completed)
        } catch (e: Exception) {
            tempDir.listFiles()?.filter { it.name.startsWith(baseName) }?.forEach { it.delete() }
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

    private fun buildBaseName(title: String): String {
        return title
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(80)
            .ifBlank { "download" }
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
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "MintDownload"
        private const val EXTRA_DOWNLOAD_ID = "download_id"
        private const val EXTRA_ORIGINAL_URL = "original_url"
        private const val EXTRA_FORMAT_ID = "format_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FORMAT = "format"
        private const val EXTRA_ESTIMATED_SIZE = "estimated_size"
        private const val EXTRA_HAS_AUDIO = "has_audio"
        private const val EXTRA_THUMBNAIL = "thumbnail"
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
        ) {
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
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(id: String) {
            instance?.processIds?.remove(id)?.let { YoutubeDL.getInstance().destroyProcessById(it) }
        }

        fun cancelBroadcast(context: Context, id: String) {
            val intent = Intent(context, CancelDownloadReceiver::class.java).apply {
                putExtra(EXTRA_CANCEL_ID, id)
            }
            context.sendBroadcast(intent)
        }
    }
}

class CancelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(DownloadService.EXTRA_CANCEL_ID)
        if (id != null) DownloadService.cancel(id)
    }
}
