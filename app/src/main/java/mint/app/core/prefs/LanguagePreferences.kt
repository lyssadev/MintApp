package mint.app.core.prefs

import android.content.Context
import android.content.SharedPreferences

object LanguagePreferences {

    const val SYSTEM = "system"

    private const val PREFS = "mint_language"
    private const val KEY_LANGUAGE = "app_language"

    fun language(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, SYSTEM) ?: SYSTEM

    fun setLanguage(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, value).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
