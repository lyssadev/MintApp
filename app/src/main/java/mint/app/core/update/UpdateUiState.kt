package mint.app.core.update

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mint.app.BuildConfig

object UpdateUiState {

    private const val PREFS = "mint_updates"
    private const val KEY_LAST_AUTO = "last_auto_check_at"
    private const val KEY_LAST_PROMPT = "last_update_prompt_at"

    sealed class Phase {
        data object Idle : Phase()
        data object Checking : Phase()
        data object UpToDate : Phase()
        data class Available(val info: AppUpdater.ReleaseInfo, val fromAuto: Boolean) : Phase()
        data class Downloading(val info: AppUpdater.ReleaseInfo) : Phase()
        data class InstallPermission(val info: AppUpdater.ReleaseInfo) : Phase()
        data class Error(val message: String) : Phase()
    }

    var phase by mutableStateOf<Phase>(Phase.Idle)
    var downloadProgress by mutableFloatStateOf(0f)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var downloadJob: Job? = null

    fun manualCheck(context: Context) {
        phase = Phase.Checking
        scope.launch {
            val info = runCatching { AppUpdater.fetchLatest() }.getOrNull()
            if (info == null) {
                phase = Phase.Error("Could not check for updates. Check your internet connection.")
                return@launch
            }
            if (AppUpdater.isNewerThan(info.tagName, BuildConfig.VERSION_NAME)) {
                phase = Phase.Available(info, fromAuto = false)
            } else {
                phase = Phase.UpToDate
            }
        }
    }

    suspend fun autoCheck(context: Context) {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_AUTO, 0L) < HOUR_MS) return
        prefs.edit().putLong(KEY_LAST_AUTO, now).apply()
        val info = runCatching { AppUpdater.fetchLatest() }.getOrNull() ?: return
        if (!AppUpdater.isNewerThan(info.tagName, BuildConfig.VERSION_NAME)) return
        if (phase !is Phase.Idle) return
        if (now - prefs.getLong(KEY_LAST_PROMPT, 0L) < HOUR_MS) return
        phase = Phase.Available(info, fromAuto = true)
    }

    fun startUpdate(context: Context) {
        val info = (phase as? Phase.Available)?.info ?: return
        if (!AppUpdater.canInstall(context)) {
            phase = Phase.InstallPermission(info)
            return
        }
        download(context, info)
    }

    fun onActivityResumed(context: Context) {
        val info = (phase as? Phase.InstallPermission)?.info ?: return
        if (AppUpdater.canInstall(context)) {
            download(context, info)
        }
    }

    private fun download(context: Context, info: AppUpdater.ReleaseInfo) {
        if (downloadJob?.isActive == true) return
        phase = Phase.Downloading(info)
        downloadProgress = 0f
        downloadJob = scope.launch {
            try {
                val apk = AppUpdater.downloadApk(context, info.apkUrl) { p -> downloadProgress = p }
                AppUpdater.installApk(context, apk)
                prefs(context).edit().putLong(KEY_LAST_PROMPT, System.currentTimeMillis()).apply()
                launch {
                    delay(60_000)
                    AppUpdater.cleanup(context)
                }
                phase = Phase.Idle
            } catch (e: Exception) {
                AppUpdater.cleanup(context)
                phase = Phase.Error(e.message ?: "Download failed")
            }
        }
    }

    fun openInstallSettings(context: Context) {
        AppUpdater.openInstallSettings(context)
    }

    fun remindLater(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_PROMPT, System.currentTimeMillis()).apply()
        dismiss()
    }

    fun dismiss() {
        downloadJob?.cancel()
        downloadJob = null
        phase = Phase.Idle
        downloadProgress = 0f
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val HOUR_MS = 60L * 60 * 1000
}
