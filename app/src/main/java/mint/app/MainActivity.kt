package mint.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import mint.app.core.prefs.AppLocale
import mint.app.core.update.AppUpdater
import mint.app.core.update.UpdateUiState
import mint.app.resolution.EngineSetup
import mint.app.ui.MintApp
import mint.app.ui.screens.HomeSession
import mint.app.ui.theme.MintTheme
import mint.app.ui.theme.ThemeController
import mint.app.ui.theme.applyThemeAwareEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUpdater.cleanup(this)
        ThemeController.init(this)
        EngineSetup.start(this)
        applyThemeAwareEdgeToEdge()
        setContent {
            LaunchedEffect(
                ThemeController.mode,
                ThemeController.presetId,
                ThemeController.dynamicColor,
                ThemeController.amoled,
            ) {
                applyThemeAwareEdgeToEdge()
            }
            MintTheme {
                MintApp()
            }
        }
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        UpdateUiState.onActivityResumed(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            val data = intent.dataString ?: return
            HomeSession.resolveUrl(data, fallbackError = "")
        }
    }
}