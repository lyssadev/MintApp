package mint.app.data

import android.app.Service
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

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentProcessId: String? = null

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

        NotificationHelper.createChannel(this)
        startForegroundCompat(NotificationHelper.buildDownloading(this, title, 0))
        DownloadManager.onPreparing(title)

        scope.launch {
            try {
                download(originalUrl, formatId, title, format, estimatedSize, hasAudio)
            } catch (e: YoutubeDL.CanceledException) {
                Log.d(TAG, "download cancelled")
            } catch (e: InterruptedException) {
                Log.d(TAG, "download interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "onStartCommand caught: ${e.javaClass.name}: ${e.message}", e)
                DownloadManager.onError(extractError(e.message))
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
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
    ) = withContext(Dispatchers.IO) {
        val fileName = buildFileName(title, format)
        val mime = mimeFor(format)
        val tempDir = File(cacheDir, "downloads")
        Log.d(TAG, "cacheDir=$cacheDir")
        Log.d(TAG, "tempDir=${tempDir.absolutePath} exists=${tempDir.exists()} before mkdirs")
        val mkdirOk = tempDir.mkdirs()
        Log.d(TAG, "mkdirs result=$mkdirOk tempDir exists=${tempDir.exists()} writable=${tempDir.canWrite()}")
        val tempFile = File(tempDir, fileName)
        Log.d(TAG, "target: Download/$fileName -> temp=${tempFile.absolutePath}")

        try {
            val formatSelector = if (!hasAudio) "$formatId+bestaudio" else formatId
            val request = YoutubeDLRequest(originalUrl)
            request.addOption("-f", formatSelector)
            request.addOption("-o", tempFile.absolutePath)
            request.addOption("--no-mtime")
            request.addOption("--no-playlist")
            request.addOption("--throttled-rate", "100K")
            request.addOption("--concurrent-fragments", "8")
            request.addOption("--retries", "10")
            request.addOption("--fragment-retries", "10")
            request.addOption("--verbose")

            Log.d(TAG, "request cmd: ${request.buildCommand().joinToString(" ")}")

            currentProcessId = "mint_download_${System.currentTimeMillis()}"

            val callback: (Float, Long, String?) -> Unit = { progress, eta, line ->
                val percent = progress.toInt().coerceIn(0, 100)
                val downloaded = if (estimatedSize > 0) {
                    (progress / 100.0 * estimatedSize).toLong()
                } else {
                    0L
                }
                if (line != null) Log.d(TAG, "ytdlp line: $line")
                Log.d(TAG, "progress: $percent% | eta: ${eta}s")
                DownloadManager.onProgress(percent, title, downloaded, estimatedSize, 0)
                NotificationHelper.updateProgress(this@DownloadService, percent, title)
            }

            val t0 = System.nanoTime()
            try {
                YoutubeDL.getInstance().execute(request, currentProcessId, callback)
            } catch (e: Exception) {
                Log.e(TAG, "yt-dlp execute FAILED: ${e.javaClass.name}: ${e.message}", e)
                throw e
            }
            val totalMs = (System.nanoTime() - t0) / 1_000_000
            Log.d(TAG, "DONE: tempFile exists=${tempFile.exists()} size=${tempFile.length()}B in ${totalMs}ms")

            if (!tempFile.exists()) {
                Log.e(TAG, "temp file missing after download! listing dir:")
                tempDir.listFiles()?.forEach { Log.e(TAG, "  - ${it.name} (${it.length()}B)") }
                throw Exception("downloaded file not found at ${tempFile.absolutePath}")
            }

            val target = FileSaver.openForWrite(this@DownloadService, fileName, mime)
            Log.d(TAG, "copying temp -> ${target.displayPath} | uri: ${target.uri}")
            tempFile.inputStream().use { input ->
                target.outputStream.use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
            tempFile.delete()

            DownloadManager.onComplete(fileName, target.displayPath)
            vibrate()
            NotificationHelper.notifyComplete(
                this@DownloadService,
                title,
                fileName,
                target.uri,
                mime,
            )
        } catch (e: Exception) {
            Log.e(TAG, "download FAILED: ${e.javaClass.name}: ${e.message}", e)
            if (tempFile.exists()) tempFile.delete()
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

    private fun buildFileName(title: String, format: String): String {
        val safe = title
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(80)
            .ifBlank { "download" }
        return "$safe.$format"
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

        fun start(
            context: Context,
            originalUrl: String,
            formatId: String,
            title: String,
            format: String,
            estimatedSize: Long = 0,
            hasAudio: Boolean = true,
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_ORIGINAL_URL, originalUrl)
                putExtra(EXTRA_FORMAT_ID, formatId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_FORMAT, format)
                putExtra(EXTRA_ESTIMATED_SIZE, estimatedSize)
                putExtra(EXTRA_HAS_AUDIO, hasAudio)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
