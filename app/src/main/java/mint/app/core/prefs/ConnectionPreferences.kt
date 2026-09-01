package mint.app.core.prefs

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

object ConnectionPreferences {

    private const val PREFS = "mint_connections"
    private const val KEY_INSTAGRAM_COOKIES = "instagram_cookies"
    private const val KEY_INSTAGRAM_USERNAME = "instagram_username"
    private const val KEY_TIKTOK_COOKIES = "tiktok_cookies"
    private const val KEY_TIKTOK_USERNAME = "tiktok_username"

    fun instagramCookies(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY_INSTAGRAM_COOKIES, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.optString(it) }
        }.getOrDefault(emptyMap())
    }

    fun saveInstagramSession(context: Context, cookies: Map<String, String>) {
        val obj = JSONObject()
        cookies.forEach { (k, v) -> obj.put(k, v) }
        prefs(context).edit()
            .putString(KEY_INSTAGRAM_COOKIES, obj.toString())
            .apply()
    }

    fun instagramUsername(context: Context): String? =
        prefs(context).getString(KEY_INSTAGRAM_USERNAME, null)?.takeIf { it.isNotBlank() }

    fun setInstagramUsername(context: Context, username: String?) {
        prefs(context).edit().putString(KEY_INSTAGRAM_USERNAME, username).apply()
    }

    fun clearInstagram(context: Context) {
        prefs(context).edit()
            .remove(KEY_INSTAGRAM_COOKIES)
            .remove(KEY_INSTAGRAM_USERNAME)
            .apply()
    }

    fun isInstagramLinked(context: Context): Boolean =
        instagramCookies(context).containsKey("sessionid")

    fun tiktokCookies(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY_TIKTOK_COOKIES, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.optString(it) }
        }.getOrDefault(emptyMap())
    }

    fun saveTikTokSession(context: Context, cookies: Map<String, String>) {
        val obj = JSONObject()
        cookies.forEach { (k, v) -> obj.put(k, v) }
        prefs(context).edit()
            .putString(KEY_TIKTOK_COOKIES, obj.toString())
            .apply()
    }

    fun tiktokUsername(context: Context): String? =
        prefs(context).getString(KEY_TIKTOK_USERNAME, null)?.takeIf { it.isNotBlank() }

    fun setTikTokUsername(context: Context, username: String?) {
        prefs(context).edit().putString(KEY_TIKTOK_USERNAME, username).apply()
    }

    fun clearTikTok(context: Context) {
        prefs(context).edit()
            .remove(KEY_TIKTOK_COOKIES)
            .remove(KEY_TIKTOK_USERNAME)
            .apply()
    }

    fun isTikTokLinked(context: Context): Boolean =
        tiktokCookies(context).containsKey("sid_tt")

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
