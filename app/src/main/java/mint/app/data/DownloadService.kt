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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
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
        @Suppress("UNCHECKED_CAST")
        val headers = intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String> ?: HashMap()

        NotificationHelper.createChannel(this)
        startForegroundCompat(NotificationHelper.buildDownloading(this, title, 0))
        DownloadManager.onProgress(0, title)

        scope.launch {
            try {
                download(url, title, format, headers)
            } catch (e: Exception) {
                DownloadManager.onError(e.message ?: "Download failed")
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun download(url: String, title: String, format: String, headers: Map<String, String>) {
        val fileName = buildFileName(title, format)
        val mime = mimeFor(format)
        val tag = "MintDownload"

        val target = FileSaver.openForWrite(this, fileName, mime)
        Log.d(tag, "target: ${target.displayPath} | uri: ${target.uri} | SDK>=29: ${Build.VERSION.SDK_INT >= 29}")
        try {
            val t0 = System.nanoTime()
            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (key, value) -> reqBuilder.addHeader(key, value) }
            val response = client.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            response.use { resp ->
                val body = resp.body ?: throw IOException("Empty response body")
                val total = body.contentLength()
                val finalUrl = resp.request.url.toString()
                Log.d(tag, "HTTP ${resp.code} | Content-Length: $total | final url: $finalUrl")
                val input: InputStream = body.byteStream()
                val output: OutputStream = BufferedOutputStream(target.outputStream, 64 * 1024)
                val buffer = downloadBuffer
                var downloaded = 0L
                var lastNotify = 0L
                var readNs = 0L
                var writeNs = 0L
                var firstByte = false

                try {
                    var lastBytes = 0L
                    var lastLog = 0L
                    var lastReadNs = 0L
                    var lastWriteNs = 0L
                    while (true) {
                        val tRead = System.nanoTime()
                        val read = input.read(buffer)
                        val dRead = System.nanoTime() - tRead
                        readNs += dRead
                        if (read < 0) break
                        if (!firstByte) {
                            firstByte = true
                            Log.d(tag, "TTFB (first byte): ${(System.nanoTime() - t0) / 1_000_000}ms")
                        }
                        val tWrite = System.nanoTime()
                        output.write(buffer, 0, read)
                        writeNs += System.nanoTime() - tWrite
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
                        if (now - lastLog >= 500) {
                            val readRatio = readNs - lastReadNs
                            val writeRatio = writeNs - lastWriteNs
                            val sum = readRatio + writeRatio
                            if (sum > 0) {
                                Log.d(tag, "progress: $downloaded/$total bytes | read ${readRatio * 100 / sum}% | write ${writeRatio * 100 / sum}%")
                            }
                            lastLog = now
                            lastReadNs = readNs
                            lastWriteNs = writeNs
                        }
                    }
                } finally {
                    output.flush()
                    output.close()
                }
                val totalNs = System.nanoTime() - t0
                Log.d(tag, "DONE: ${downloaded}B in ${totalNs / 1_000_000}ms | " +
                    "read ${readNs / 1_000_000}ms | write ${writeNs / 1_000_000}ms | " +
                    "avg read bytes: ${if (downloaded > 0) downloaded / (readNs / 1_000_000 + 1) else 0} B/ms")
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
        private const val EXTRA_HEADERS = "headers"

        fun start(context: Context, url: String, title: String, format: String, headers: Map<String, String> = emptyMap()) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_FORMAT, format)
                putExtra(EXTRA_HEADERS, HashMap(headers))
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
