package mint.app.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext


private const val THEME_ANIMATION_DURATION = 450

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

    val targetScheme: ColorScheme = if (ThemeController.dynamicColor) {
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

    val colorScheme = targetScheme.animated()

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}

@Composable
private fun animatedThemeColor(color: Color): Color = animateColorAsState(
    targetValue = color,
    animationSpec = tween(durationMillis = THEME_ANIMATION_DURATION, easing = FastOutSlowInEasing),
    label = "themeColor",
).value

@Composable
private fun ColorScheme.animated(): ColorScheme = copy(
    primary = animatedThemeColor(primary),    onPrimary = animatedThemeColor(onPrimary),
    primaryContainer = animatedThemeColor(primaryContainer),
    onPrimaryContainer = animatedThemeColor(onPrimaryContainer),
    inversePrimary = animatedThemeColor(inversePrimary),
    secondary = animatedThemeColor(secondary),
    onSecondary = animatedThemeColor(onSecondary),
    secondaryContainer = animatedThemeColor(secondaryContainer),
    onSecondaryContainer = animatedThemeColor(onSecondaryContainer),
    tertiary = animatedThemeColor(tertiary),
    onTertiary = animatedThemeColor(onTertiary),
    tertiaryContainer = animatedThemeColor(tertiaryContainer),
    onTertiaryContainer = animatedThemeColor(onTertiaryContainer),
    background = animatedThemeColor(background),
    onBackground = animatedThemeColor(onBackground),
    surface = animatedThemeColor(surface),
    onSurface = animatedThemeColor(onSurface),
    surfaceVariant = animatedThemeColor(surfaceVariant),
    onSurfaceVariant = animatedThemeColor(onSurfaceVariant),
    surfaceTint = animatedThemeColor(surfaceTint),
    inverseSurface = animatedThemeColor(inverseSurface),
    inverseOnSurface = animatedThemeColor(inverseOnSurface),
    error = animatedThemeColor(error),
    onError = animatedThemeColor(onError),
    errorContainer = animatedThemeColor(errorContainer),
    onErrorContainer = animatedThemeColor(onErrorContainer),
    outline = animatedThemeColor(outline),
    outlineVariant = animatedThemeColor(outlineVariant),
    scrim = animatedThemeColor(scrim),
    surfaceBright = animatedThemeColor(surfaceBright),
    surfaceDim = animatedThemeColor(surfaceDim),
    surfaceContainer = animatedThemeColor(surfaceContainer),
    surfaceContainerHigh = animatedThemeColor(surfaceContainerHigh),
    surfaceContainerHighest = animatedThemeColor(surfaceContainerHighest),
    surfaceContainerLow = animatedThemeColor(surfaceContainerLow),
    surfaceContainerLowest = animatedThemeColor(surfaceContainerLowest),
)
