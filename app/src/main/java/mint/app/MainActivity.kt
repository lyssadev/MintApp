package mint.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import mint.app.core.update.AppUpdater
import mint.app.core.update.UpdateUiState
import mint.app.resolution.ResolverRegistry
import mint.app.ui.MintApp
import mint.app.ui.screens.HomeSession
import mint.app.ui.theme.MintTheme
import mint.app.ui.theme.ThemeController
import mint.app.ui.theme.applyThemeAwareEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUpdater.cleanup(this)
        ThemeController.init(this)
        ResolverRegistry.init(this)
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
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                extractUrl(text)?.let { HomeSession.resolveUrl(it) }
            }
            Intent.ACTION_VIEW -> {
                val data = intent.dataString ?: return
                HomeSession.resolveUrl(data)
            }
        }
    }

    private fun extractUrl(text: String): String? {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> {
                trimmed.split("\\s+".toRegex())
                    .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
            }
        }
    }
}