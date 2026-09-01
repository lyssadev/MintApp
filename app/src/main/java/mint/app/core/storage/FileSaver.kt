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
            val uniqueName = uniqueNameForMediaStore(context, fileName, mimeType, relativePath)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
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
                displayPath = "/sdcard/Download/$relativeSub/$uniqueName",
            )
        } else {
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(File(base, subfolder), typeDir)
            dir.mkdirs()
            val uniqueName = uniqueNameForFs(dir, fileName)
            val file = File(dir, uniqueName)
            OutputTarget(
                outputStream = FileOutputStream(file),
                uri = Uri.fromFile(file),
                displayPath = file.absolutePath,
            )
        }
    }

    private fun uniqueNameForMediaStore(
        context: Context,
        fileName: String,
        mimeType: String,
        relativePath: String,
    ): String {
        val existing = mutableSetOf<String>()
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$relativePath/%"),
            null,
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                cursor.getString(idx)?.let { existing.add(it) }
            }
        }
        if (fileName !in existing) return fileName
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "").let { if (it == fileName) "" else ".$it" }
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate !in existing) return candidate
            n++
        }
    }

    private fun uniqueNameForFs(dir: File, fileName: String): String {
        if (!File(dir, fileName).exists()) return fileName
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "").let { if (it == fileName) "" else ".$it" }
        var n = 1
        while (File(dir, "$base ($n)$ext").exists()) n++
        return "$base ($n)$ext"
    }
}