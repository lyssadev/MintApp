package mint.app.data

import android.content.Context
import android.content.SharedPreferences

object DownloadPreferences {

    private const val PREFS = "mint_download"
    private const val KEY_SUBFOLDER = "download_subfolder"
    private const val KEY_PERMISSIONS_ASKED = "permissions_asked"

    fun subfolder(context: Context): String =
        prefs(context).getString(KEY_SUBFOLDER, "") ?: ""

    fun setSubfolder(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SUBFOLDER, value.trim().trim('/')).apply()
    }

    fun permissionsAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PERMISSIONS_ASKED, false)

    fun setPermissionsAsked(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PERMISSIONS_ASKED, value).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
