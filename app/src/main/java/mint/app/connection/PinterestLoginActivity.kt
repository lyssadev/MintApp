package mint.app.connection

import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import mint.app.R
import mint.app.core.prefs.ConnectionPreferences
import mint.app.core.util.ChromeDebug
import mint.app.ui.components.IndeterminateProgressBar
import mint.app.ui.theme.MintTheme
import mint.app.ui.theme.ThemeController
import mint.app.ui.theme.applyThemeAwareEdgeToEdge

class PinterestLoginActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(mint.app.core.prefs.AppLocale.apply(newBase))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null
    private var finished = false
    private var loading by mutableStateOf(true)
    private val ua = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.6613.127 Mobile Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeController.init(this)
        ChromeDebug.init()
        applyThemeAwareEdgeToEdge()

        val container = FrameLayout(this)
        val webView = buildWebView()

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        CookieManager.getInstance().removeAllCookies(null)

        val client = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                loading = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loading = false
                checkCookies()
            }
        }

        webView.webViewClient = client

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                loading = newProgress < 100
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                val popup = WebView(this@PinterestLoginActivity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportMultipleWindows(true)
                    settings.userAgentString = ua
                    webViewClient = client
                }
                container.addView(
                    popup,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popup
                resultMsg?.sendToTarget()
                return true
            }
        }

        container.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        setContent {
            MintTheme {
                LoginScreen(
                    container = container,
                    loading = loading,
                    onClose = { cancelLogin() },
                )
            }
        }

        webView.loadUrl("https://www.pinterest.com/login/")

        pollJob = scope.launch {
            while (!finished) {
                checkCookies()
                delay(500)
            }
        }
    }

    private fun buildWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.userAgentString = ua
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
    }

    private fun cancelLogin() {
        if (finished) return
        Toast.makeText(this, "Login cancelled", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onBackPressed() {
        cancelLogin()
    }

    private fun checkCookies() {
        if (finished) return
        val cookies = CookieManager.getInstance().getCookie("https://www.pinterest.com") ?: return
        val parsed = cookies.split(";").associate {
            val parts = it.trim().split("=", limit = 2)
            parts.first() to parts.getOrElse(1) { "" }
        }
        if (parsed["_auth"] == "1" && parsed.containsKey("_pinterest_sess") && parsed.containsKey("csrftoken")) {
            finished = true
            pollJob?.cancel()
            ConnectionPreferences.savePinterestSession(this, parsed)
            Toast.makeText(this, "Pinterest linked successfully", Toast.LENGTH_SHORT).show()
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
private fun LoginScreen(
    container: FrameLayout,
    loading: Boolean,
    onClose: () -> Unit,
) {
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
                    contentDescription = stringResource(R.string.cd_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.login_pinterest_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (loading) {
            IndeterminateProgressBar(
                modifier = Modifier.fillMaxWidth(),
                rounded = false,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(factory = { container })
        }
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding(),
        )
    }
}