package mint.app.connection

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.lyxnx.compose.ui.tablericons.TablerIcons
import io.github.lyxnx.compose.ui.tablericons.outline.X
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mint.app.core.prefs.ConnectionPreferences
import mint.app.ui.theme.MintTheme
import mint.app.ui.theme.ThemeController
import mint.app.ui.theme.applyThemeAwareEdgeToEdge

class InstagramLoginActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null
    private var finished = false
    private val ua = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.6613.127 Mobile Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeController.init(this)
        applyThemeAwareEdgeToEdge()

        val webView = buildWebView()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().removeAllCookies(null)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                checkCookies()
            }
        }

        setContent {
            MintTheme {
                LoginScreen(webView = webView, onClose = { finish() })
            }
        }

        webView.loadUrl("https://www.instagram.com/accounts/login/")

        pollJob = scope.launch {
            while (!finished) {
                checkCookies()
                delay(500)
            }
        }
    }

    private fun buildWebView(): WebView = WebView(this).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = ua
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
    }

    private fun checkCookies() {
        if (finished) return
        val cookies = CookieManager.getInstance().getCookie("https://www.instagram.com") ?: return
        val parsed = cookies.split(";").associate {
            val parts = it.trim().split("=", limit = 2)
            parts.first() to parts.getOrElse(1) { "" }
        }
        if (parsed.containsKey("sessionid")) {
            finished = true
            pollJob?.cancel()
            ConnectionPreferences.saveInstagramSession(this, parsed)
            Toast.makeText(this, "Instagram linked successfully", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onDestroy() {
        finished = true
        pollJob?.cancel()
        super.onDestroy()
    }

    companion object {
        const val RESULT_LINKED = RESULT_OK
    }
}

@Composable
private fun LoginScreen(webView: WebView, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = TablerIcons.Outline.X,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Instagram Login",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(factory = { webView })
        }
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding(),
        )
    }
}
