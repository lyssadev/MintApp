package mint.app.resolution

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object EngineSetup {

    private const val PREFS = "mint_engine_setup"
    private const val KEY_SETUP_VERSION = "setup_version_code"

    var ready by mutableStateOf(false)
        private set

    var setupRequired by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        setupRequired = prefs(appContext).getInt(KEY_SETUP_VERSION, 0) != appContext.getVersionCode()
        scope.launch {
            withContext(Dispatchers.IO) {
                ResolverRegistry.init(appContext)
            }
            if (setupRequired) {
                prefs(appContext).edit().putInt(KEY_SETUP_VERSION, appContext.getVersionCode()).apply()
            }
            ready = true
        }
    }

    suspend fun await() {
        while (!ready) {
            kotlinx.coroutines.delay(100)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Context.getVersionCode(): Int = try {
        packageManager.getPackageInfo(packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                it.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                it.versionCode
            }
        }
    } catch (e: Exception) {
        0
    }
}
