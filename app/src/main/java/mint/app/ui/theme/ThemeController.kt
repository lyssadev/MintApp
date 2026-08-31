package mint.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode { LIGHT, DARK, SYSTEM }

object ThemeController {

    private const val PREFS_NAME = "mint_theme_prefs"
    private const val KEY_PRESET = "preset_id"
    private const val KEY_MODE = "mode"
    private const val KEY_DYNAMIC = "dynamic_color"

    var presetId by mutableStateOf(ThemePresets.DEFAULT_ID)
        private set
    var mode by mutableStateOf(ThemeMode.LIGHT)
        private set
    var dynamicColor by mutableStateOf(false)
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val p = prefs!!
        presetId = p.getString(KEY_PRESET, ThemePresets.DEFAULT_ID)
            ?.takeIf { ThemePresets.byId.containsKey(it) }
            ?: ThemePresets.DEFAULT_ID
        mode = p.getString(KEY_MODE, null)
            ?.let { value -> runCatching { ThemeMode.valueOf(value) }.getOrNull() }
            ?: ThemeMode.LIGHT
        dynamicColor = p.getBoolean(KEY_DYNAMIC, false)
    }

    fun updatePreset(id: String) {
        presetId = if (ThemePresets.byId.containsKey(id)) id else ThemePresets.DEFAULT_ID
        save()
    }

    fun updateMode(value: ThemeMode) {
        mode = value
        save()
    }

    fun updateDynamicColor(value: Boolean) {
        dynamicColor = value
        save()
    }

    fun isDarkMode(): Boolean = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemInDarkMode()
    }

    private fun systemInDarkMode(): Boolean =
        (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun save() {
        prefs?.edit()
            ?.putString(KEY_PRESET, presetId)
            ?.putString(KEY_MODE, mode.name)
            ?.putBoolean(KEY_DYNAMIC, dynamicColor)
            ?.apply()
    }
}
