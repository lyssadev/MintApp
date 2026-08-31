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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val downloadBuffer = ByteArray(64 * 1024) // 64KB buffer

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: run { stopSelf(); return START_NOT_STICKY }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Download"
        val format = intent.getStringExtra(EXTRA_FORMAT) ?: "mp4"

        NotificationHelper.createChannel(this)
        startForegroundCompat(NotificationHelper.buildDownloading(this, title, 0))
        DownloadManager.onProgress(0, title)

        scope.launch {
            try {
                download(url, title, format)
            } catch (e: Exception) {
                DownloadManager.onError(e.message ?: "Download failed")
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun download(url: String, title: String, format: String) {
        val fileName = buildFileName(title, format)
        val mime = mimeFor(format)

        val target = FileSaver.openForWrite(this, fileName, mime)
        try {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            response.use { resp ->
                val body = resp.body ?: throw IOException("Empty response body")
                val total = body.contentLength()
                val input: InputStream = body.byteStream()
                val output: OutputStream = target.outputStream
                val buffer = downloadBuffer
                var downloaded = 0L
                var lastNotify = 0L

                try {
                    var lastBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val percent = if (total > 0) {
                            ((downloaded * 100) / total).toInt()
                        } else {
                            -1
                        }
                        val now = System.currentTimeMillis()
                        if (percent >= 0 && now - lastNotify >= 250) {
                            val elapsed = now - lastNotify
                            val delta = downloaded - lastBytes
                            val speed = if (elapsed > 0) (delta * 1000 / elapsed) else 0L
                            lastNotify = now
                            lastBytes = downloaded
                            DownloadManager.onProgress(percent, title, downloaded, total, speed)
                            NotificationHelper.updateProgress(this, percent, title)
                        }
                    }
                } finally {
                    output.flush()
                    output.close()
                }
            }
        } catch (e: Exception) {
            target.uri?.let { uri ->
                runCatching {
                    contentResolver.delete(uri, null, null)
                }
            }
            throw e
        }

        DownloadManager.onComplete(fileName, target.displayPath)
        vibrate()
        NotificationHelper.notifyComplete(
            this,
            title,
            fileName,
            target.uri,
            mime,
        )
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
        scope.cancel()
        super.onDestroy()
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
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FORMAT = "format"

        fun start(context: Context, url: String, title: String, format: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_FORMAT, format)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
