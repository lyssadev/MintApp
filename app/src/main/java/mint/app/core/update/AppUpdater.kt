package mint.app.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mint.app.core.util.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val REPO_API = "https://api.github.com/repos/lyssadev/MintApp/releases/latest"
    private const val DIR = "updates"
    private const val APK_NAME = "mint-update.apk"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class ReleaseInfo(
        val tagName: String,
        val name: String,
        val body: String,
        val apkUrl: String,
        val apkSize: Long,
    )

    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(REPO_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MintApp")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Logger.w(TAG, "fetchLatest: HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
                val assets = json.optJSONArray("assets") ?: JSONArray()
                val apk = pickApk(assets) ?: return@withContext null
                val info = ReleaseInfo(
                    tagName = json.optString("tag_name", ""),
                    name = json.optString("name", json.optString("tag_name", "")),
                    body = json.optString("body", "").trim(),
                    apkUrl = apk.optString("browser_download_url", ""),
                    apkSize = apk.optLong("size", 0L),
                )
                if (info.tagName.isBlank() || info.apkUrl.isBlank()) {
                    Logger.w(TAG, "fetchLatest: incomplete release data")
                    return@withContext null
                }
                Logger.d(TAG, "fetchLatest: ${info.tagName} -> ${info.apkUrl}")
                info
            }
        } catch (e: Exception) {
            Logger.w(TAG, "fetchLatest: failed", e)
            null
        }
    }

    private fun pickApk(assets: JSONArray): JSONObject? {
        val apks = (0 until assets.length()).mapNotNull { i -> assets.optJSONObject(i) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        val abi = if (Build.SUPPORTED_ABIS.any { it.contains("arm64") }) "arm64-v8a" else "armeabi-v7a"
        return apks.firstOrNull { it.optString("name").contains("universal", ignoreCase = true) }
            ?: apks.firstOrNull { it.optString("name").contains(abi, ignoreCase = true) }
            ?: apks.first()
    }

    fun isNewerThan(latestTag: String, currentVersion: String): Boolean {
        val latest = parseVersion(latestTag)
        val current = parseVersion(currentVersion)
        val max = maxOf(latest.size, current.size)
        for (i in 0 until max) {
            val a = latest.getOrElse(i) { 0 }
            val b = current.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parseVersion(value: String): List<Int> =
        value.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+', '_')
            .mapNotNull { it.trim().toIntOrNull() }

    suspend fun downloadApk(context: Context, url: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, DIR)
            dir.mkdirs()
            val file = File(dir, APK_NAME)
            file.delete()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MintApp")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw Exception("Download failed: HTTP ${resp.code}")
                }
                val total = resp.body?.contentLength() ?: -1L
                resp.body?.byteStream()?.use { input ->
                    file.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buf)
                            if (read < 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                } ?: throw Exception("Download failed: empty response")
            }
            Logger.d(TAG, "downloadApk: ${file.length()} bytes -> $file")
            file
        }

    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Logger.d(TAG, "installApk: install intent fired for $apk")
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(context: Context) {
        runCatching {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(intent)
        }
    }

    fun cleanup(context: Context) {
        val dir = File(context.cacheDir, DIR)
        if (dir.exists()) {
            dir.deleteRecursively()
            Logger.d(TAG, "cleanup: deleted $dir")
        }
    }
}
