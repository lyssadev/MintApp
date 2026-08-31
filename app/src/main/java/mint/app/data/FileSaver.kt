package mint.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
    ): OutputTarget {
        val subfolder = DownloadPreferences.subfolder(context)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath = buildString {
                append(Environment.DIRECTORY_DOWNLOADS)
                if (subfolder.isNotBlank()) {
                    append('/')
                    append(subfolder)
                }
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
                displayPath = buildString {
                    append("/sdcard/Download/")
                    if (subfolder.isNotBlank()) {
                        append(subfolder)
                        append('/')
                    }
                    append(fileName)
                },
            )
        } else {
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = if (subfolder.isNotBlank()) File(base, subfolder) else base
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
