package mint.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import mint.app.MainActivity
import mint.app.R

object NotificationHelper {

    const val CHANNEL_ID = "downloads"
    private const val NOTIFICATION_ID = 1

    private var channelCreated = false

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

    private fun viewIntent(context: Context, uri: Uri, mime: String): PendingIntent? {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return PendingIntent.getActivity(
            context, 1, intent, PendingIntent.FLAG_IMMUTABLE,
        ).takeIf { intent.resolveActivity(context.packageManager) != null }
    }

    fun buildDownloading(context: Context, title: String, progress: Int): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download_animated)
            .setContentTitle(title)
            .setContentText("$progress%")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(context))
            .build()
    }

    fun buildComplete(context: Context, title: String, fileName: String, fileUri: Uri?, mime: String): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_check)
            .setContentTitle(title)
            .setContentText("Download complete — $fileName")
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
        if (fileUri != null) {
            viewIntent(context, fileUri, mime)?.let { builder.setContentIntent(it) }
        }
        return builder.build()
    }

    fun updateProgress(context: Context, progress: Int, title: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildDownloading(context, title, progress))
    }

    fun notifyComplete(context: Context, title: String, fileName: String, fileUri: Uri?, mime: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildComplete(context, title, fileName, fileUri, mime))
    }

    fun notifyError(context: Context, title: String, error: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText("Download failed — $error")
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }
}
