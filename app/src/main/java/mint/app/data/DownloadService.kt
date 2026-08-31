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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentProcessId: String? = null
    @Volatile private var canceled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val originalUrl = intent?.getStringExtra(EXTRA_ORIGINAL_URL)
            ?: run { stopSelf(); return START_NOT_STICKY }
        val formatId = intent.getStringExtra(EXTRA_FORMAT_ID)
            ?: run { stopSelf(); return START_NOT_STICKY }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Download"
        val format = intent.getStringExtra(EXTRA_FORMAT) ?: "mp4"
        val estimatedSize = intent.getLongExtra(EXTRA_ESTIMATED_SIZE, 0L)
        val hasAudio = intent.getBooleanExtra(EXTRA_HAS_AUDIO, true)
        val thumbnailUrl = intent.getStringExtra(EXTRA_THUMBNAIL)

        canceled = false
        NotificationHelper.createChannel(this)
        startForegroundCompat(NotificationHelper.buildDownloading(this, title, 0, thumbnailUrl))
        DownloadManager.onPreparing(title, thumbnailUrl)

        scope.launch {
            try {
                download(originalUrl, formatId, title, format, estimatedSize, hasAudio, thumbnailUrl)
            } catch (e: YoutubeDL.CanceledException) {
                Log.d(TAG, "download cancelled")
                canceled = true
                DownloadManager.reset()
            } catch (e: InterruptedException) {
                Log.d(TAG, "download interrupted")
                canceled = true
                DownloadManager.reset()
            } catch (e: Exception) {
                currentProcessId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
                NotificationHelper.notifyError(this@DownloadService, title, extractError(e.message))
                DownloadManager.onError(extractError(e.message))
            } finally {
                stopForeground(if (canceled) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun download(
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

            currentProcessId = "mint_download_${System.currentTimeMillis()}"
            activeProcessId.set(currentProcessId)

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
                    DownloadManager.onProcessing(title)
                    NotificationHelper.notifyProcessing(this@DownloadService, title, thumbnailUrl)
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
                    Log.d(TAG, "progress: $percent% | eta: ${eta}s")
                    DownloadManager.onProgress(percent, title, downloaded, estimatedSize, speed)
                    NotificationHelper.updateProgress(this@DownloadService, percent, title, thumbnailUrl)
                }
            }

            YoutubeDL.getInstance().execute(request, currentProcessId, callback)
            if (canceled) throw YoutubeDL.CanceledException()
            activeProcessId.compareAndSet(currentProcessId, null)

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

            DownloadHistory.add(
                this@DownloadService,
                DownloadEntry(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    fileName = finalName,
                    savedPath = target.displayPath,
                    uri = target.uri?.toString(),
                    mime = finalMime,
                    thumbnailUrl = thumbnailUrl,
                    sizeBytes = sizeBytes,
                    timestamp = System.currentTimeMillis(),
                ),
            )

            DownloadManager.onComplete(finalName, target.displayPath)
            vibrate()
            NotificationHelper.notifyComplete(
                this@DownloadService,
                title,
                finalName,
                target.uri,
                finalMime,
            )
        } catch (e: Exception) {
            tempDir.listFiles()?.filter { it.name.startsWith(baseName) }?.forEach { it.delete() }
            throw e
        }
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(1, notification)
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
        activeProcessId.compareAndSet(currentProcessId, null)
        currentProcessId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
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
        private const val EXTRA_ORIGINAL_URL = "original_url"
        private const val EXTRA_FORMAT_ID = "format_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FORMAT = "format"
        private const val EXTRA_ESTIMATED_SIZE = "estimated_size"
        private const val EXTRA_HAS_AUDIO = "has_audio"
        private const val EXTRA_THUMBNAIL = "thumbnail"

        private val activeProcessId = AtomicReference<String?>(null)

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
            val intent = Intent(context, DownloadService::class.java).apply {
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

        fun cancelActive() {
            activeProcessId.getAndSet(null)?.let { YoutubeDL.getInstance().destroyProcessById(it) }
        }
    }
}

class CancelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DownloadService.cancelActive()
    }
}
