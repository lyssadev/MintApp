package mint.app.core.prefs

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object AppLocale {

    val SUPPORTED = listOf(
        LanguagePreferences.SYSTEM to null,
        "en" to Locale.ENGLISH,
        "es" to Locale("es"),
        "pt-BR" to Locale("pt", "BR"),
        "fr" to Locale.FRENCH,
        "de" to Locale.GERMAN,
        "ru" to Locale("ru"),
        "in" to Locale("in"),
        "ja" to Locale.JAPANESE,
    )

    fun currentTag(context: Context): String = LanguagePreferences.language(context)

    fun localeFor(tag: String): Locale? = SUPPORTED.firstOrNull { it.first == tag }?.second

    fun systemTag(): String {
        val sys = Locale.getDefault()
        val full = "${sys.language}-${sys.country}"
        return when {
            SUPPORTED.any { it.first == full } -> full
            SUPPORTED.any { it.first == sys.language } -> sys.language
            else -> "en"
        }
    }

    fun effectiveTag(context: Context): String {
        val chosen = currentTag(context)
        return if (chosen == LanguagePreferences.SYSTEM) systemTag() else chosen
    }

    fun apply(context: Context): Context {
        val tag = effectiveTag(context)
        val locale = localeFor(tag) ?: return context
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    fun setLanguage(activity: Activity, tag: String) {
        LanguagePreferences.setLanguage(activity, tag)
        activity.recreate()
    }
}
