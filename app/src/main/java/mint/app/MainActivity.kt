package mint.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import mint.app.ui.MintApp
import mint.app.ui.theme.MintTheme
import mint.app.ui.theme.ThemeController
import mint.app.ui.theme.applyThemeAwareEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeController.init(this)
        applyThemeAwareEdgeToEdge()
        setContent {
            LaunchedEffect(
                ThemeController.mode,
                ThemeController.presetId,
                ThemeController.dynamicColor,
            ) {
                applyThemeAwareEdgeToEdge()
            }
            MintTheme {
                MintApp()
            }
        }
    }
}
