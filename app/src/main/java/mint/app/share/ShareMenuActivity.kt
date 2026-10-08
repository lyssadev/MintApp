package mint.app.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import mint.app.BuildConfig
import mint.app.MainActivity
import mint.app.core.prefs.AppLocale
import mint.app.core.prefs.DownloadPreferences
import mint.app.core.util.Logger
import mint.app.resolution.EngineSetup
import mint.app.resolution.ResolverRegistry
import mint.app.ui.share.ShareMenu
import mint.app.ui.theme.MintTheme
import mint.app.ui.theme.ThemeController
import mint.app.ui.theme.applyThemeAwareEdgeToEdge

class ShareMenuActivity : ComponentActivity() {

    private var link by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.enabled = BuildConfig.DEBUG || DownloadPreferences.loggerEnabled(this)
        ThemeController.init(this)
        EngineSetup.start(this)
        applyThemeAwareEdgeToEdge()

        val incoming = extractLink(intent)
        if (incoming == null) {
            finish()
            return
        }
        if (!ResolverRegistry.isYouTube(incoming)) {
            openInApp(incoming)
            return
        }
        link = incoming

        setContent {
            MintTheme {
                link?.let { current ->
                    ShareMenu(
                        link = current,
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = extractLink(intent)
        if (incoming == null) {
            finish()
            return
        }
        if (!ResolverRegistry.isYouTube(incoming)) {
            openInApp(incoming)
            return
        }
        link = incoming
    }

    private fun openInApp(link: String) {
        Logger.d(TAG, "openInApp: routing non-youtube link to main app")
        val target = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(link)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(target)
        finish()
    }

    private fun extractLink(intent: Intent?): String? {
        val raw = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString ?: return null
        return parseUrl(raw)
    }

    private fun parseUrl(text: String): String? {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
                trimmed.substringBefore(' ').trim()
            else -> trimmed.split("\\s+".toRegex())
                .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
        }
    }

    companion object {
        private const val TAG = "ShareMenuActivity"
    }
}
