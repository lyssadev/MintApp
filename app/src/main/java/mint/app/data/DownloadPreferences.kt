package mint.app.data

import android.content.Context
import android.content.SharedPreferences

object DownloadPreferences {

    private const val PREFS = "mint_download"
    private const val KEY_SUBFOLDER = "download_subfolder"
    private const val KEY_VIDEO_DIR = "video_dir"
    private const val KEY_AUDIO_DIR = "audio_dir"
    private const val KEY_PERMISSIONS_ASKED = "permissions_asked"

    fun subfolder(context: Context): String =
        prefs(context).getString(KEY_SUBFOLDER, "MintApp") ?: "MintApp"

    fun setSubfolder(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SUBFOLDER, value.trim().trim('/')).apply()
    }

    fun videoDir(context: Context): String =
        prefs(context).getString(KEY_VIDEO_DIR, "videos") ?: "videos"

    fun setVideoDir(context: Context, value: String) {
        prefs(context).edit().putString(KEY_VIDEO_DIR, value.trim().trim('/')).apply()
    }

    fun audioDir(context: Context): String =
        prefs(context).getString(KEY_AUDIO_DIR, "audios") ?: "audios"

    fun setAudioDir(context: Context, value: String) {
        prefs(context).edit().putString(KEY_AUDIO_DIR, value.trim().trim('/')).apply()
    }

    fun permissionsAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PERMISSIONS_ASKED, false)

    fun setPermissionsAsked(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PERMISSIONS_ASKED, value).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
