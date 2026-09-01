package mint.app.core.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import mint.app.core.prefs.DownloadPreferences
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

data class OutputTarget(
    val outputStream: OutputStream,
    val uri: Uri?,
    val displayPath: String,
)

object FileSaver {

    fun openForWrite(
        context: Context,
        fileName: String,
        mimeType: String,
        isAudio: Boolean,
        isImage: Boolean = false,
    ): OutputTarget {
        val subfolder = DownloadPreferences.subfolder(context)
        val typeDir = when {
            isAudio -> DownloadPreferences.audioDir(context)
            isImage -> DownloadPreferences.imageDir(context)
            else -> DownloadPreferences.videoDir(context)
        }
        val relativeSub = buildString {
            append(subfolder)
            if (typeDir.isNotBlank()) {
                append('/')
                append(typeDir)
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = buildString {
                append(Environment.DIRECTORY_DOWNLOADS)
                append('/')
                append(relativeSub)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            ) ?: throw IOException("Could not create file in Downloads")
            val stream = context.contentResolver.openOutputStream(uri)
                ?: throw IOException("Could not open output stream")
            OutputTarget(
                outputStream = stream,
                uri = uri,
                displayPath = "/sdcard/Download/$relativeSub/$fileName",
            )
        } else {
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(File(base, subfolder), typeDir)
            dir.mkdirs()
            val file = File(dir, fileName)
            OutputTarget(
                outputStream = FileOutputStream(file),
                uri = Uri.fromFile(file),
                displayPath = file.absolutePath,
            )
        }
    }
}