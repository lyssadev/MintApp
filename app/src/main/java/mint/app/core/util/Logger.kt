package mint.app.core.util

import android.util.Log

object Logger {
    @Volatile
    var enabled = false

    private const val PREFIX = "Mint."

    fun d(tag: String, msg: String) {
        if (!enabled) return
        Log.d("$PREFIX$tag", msg)
    }

    fun d(tag: String, msg: String, e: Throwable?) {
        if (!enabled) return
        Log.d("$PREFIX$tag", "$msg\n${stackTrace(e)}")
    }

    fun w(tag: String, msg: String) {
        if (!enabled) return
        Log.w("$PREFIX$tag", msg)
    }

    fun w(tag: String, msg: String, e: Throwable?) {
        if (!enabled) return
        Log.w("$PREFIX$tag", "$msg\n${stackTrace(e)}")
    }

    fun e(tag: String, msg: String) {
        if (!enabled) return
        Log.e("$PREFIX$tag", msg)
    }

    fun e(tag: String, msg: String, e: Throwable?) {
        if (!enabled) return
        Log.e("$PREFIX$tag", msg, e)
    }

    private fun stackTrace(e: Throwable?): String {
        if (e == null) return ""
        val sw = java.io.StringWriter()
        val pw = java.io.PrintWriter(sw)
        e.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }
}