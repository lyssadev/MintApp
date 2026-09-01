package mint.app.core.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import mint.app.core.model.DownloadItem
import mint.app.MainActivity
import mint.app.R
import mint.app.data.CancelDownloadReceiver
import mint.app.data.DownloadService

object NotificationHelper {

    const val CHANNEL_ID = "downloads"
    private const val BASE_ID = 100
    private const val COMPLETED_LIST_ID = 20000

    private var channelCreated = false

    fun idFor(downloadId: String): Int =
        BASE_ID + (downloadId.hashCode() and 0x7fffffff) % 10000

    fun createChannel(context: Context) {
        if (channelCreated) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Download progress and completion"
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
        channelCreated = true
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelIntent(context: Context, downloadId: String): PendingIntent {
        val intent = Intent(context, CancelDownloadReceiver::class.java).apply {
            putExtra(DownloadService.EXTRA_CANCEL_ID, downloadId)
        }
        return PendingIntent.getBroadcast(
            context, idFor(downloadId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private var cachedIconUrl: String? = null
    private var cachedIcon: android.graphics.Bitmap? = null

    private fun largeIcon(context: Context, url: String?): android.graphics.Bitmap? {
        if (url.isNullOrBlank()) return null
        if (url == cachedIconUrl) return cachedIcon
        return try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val bitmap = connection.inputStream.use {
                android.graphics.BitmapFactory.decodeStream(it)
            }
            cachedIconUrl = url
            cachedIcon = bitmap
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    fun buildDownloading(context: Context, title: String, progress: Int, thumbnailUrl: String? = null): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download_animated)
            .setContentTitle(title)
            .setContentText("$progress%")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(context))
        largeIcon(context, thumbnailUrl)?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    fun updateProgress(context: Context, downloadId: String, progress: Int, title: String, thumbnailUrl: String? = null) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download_animated)
            .setContentTitle(title)
            .setContentText("$progress%")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(context))
            .addAction(0, "Cancel", cancelIntent(context, downloadId))
        largeIcon(context, thumbnailUrl)?.let { builder.setLargeIcon(it) }
        nm.notify(idFor(downloadId), builder.build())
    }

    fun notifyProcessing(context: Context, downloadId: String, title: String, thumbnailUrl: String? = null) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download_animated)
            .setContentTitle(title)
            .setContentText("Processing...")
            .setProgress(100, 100, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(context))
            .addAction(0, "Cancel", cancelIntent(context, downloadId))
        largeIcon(context, thumbnailUrl)?.let { builder.setLargeIcon(it) }
        nm.notify(idFor(downloadId), builder.build())
    }

    fun notifyCompletedList(context: Context, completed: List<DownloadItem>) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val names = completed.map { it.fileName.ifBlank { it.title } }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_check)
            .setContentTitle("Downloads complete")
            .setContentText("${names.size} file(s) downloaded")
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
        val inbox = NotificationCompat.InboxStyle()
            .setBigContentTitle("${names.size} file(s) downloaded")
        names.takeLast(10).forEach { inbox.addLine(it) }
        if (names.size > 10) {
            inbox.setSummaryText("+${names.size - 10} more")
        }
        builder.setStyle(inbox)
        nm.notify(COMPLETED_LIST_ID, builder.build())
    }

    fun notifyError(context: Context, downloadId: String, title: String, error: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText("Download failed — $error")
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        nm.notify(idFor(downloadId), notification)
    }

    fun dismiss(context: Context, downloadId: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(idFor(downloadId))
    }

    fun dismissCompletedList(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(COMPLETED_LIST_ID)
    }
}