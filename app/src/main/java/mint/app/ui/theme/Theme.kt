package mint.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun MintTheme(
    content: @Composable () -> Unit,
) {
    val dark = when (ThemeController.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current

    val colorScheme: ColorScheme = if (ThemeController.dynamicColor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dynamic = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (dark && ThemeController.amoled) dynamic.amoledScheme() else dynamic
        } else {
            ThemePresets.resolve(ThemePresets.DEFAULT_ID, dark).toColorScheme()
        }
    } else {
        val palette = ThemePresets.resolve(ThemeController.presetId, dark)
        if (dark && ThemeController.amoled) {
            palette.toColorScheme().amoledScheme()
        } else {
            palette.toColorScheme()
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
