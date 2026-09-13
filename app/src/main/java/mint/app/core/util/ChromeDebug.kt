package mint.app.core.util

import android.webkit.WebView
import mint.app.BuildConfig

object ChromeDebug {
    fun init() {
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }
}