package mint.app.resolution

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object EngineSetup {

    var ready by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(context: Context) {
        if (ready) return
        val appContext = context.applicationContext
        scope.launch {
            withContext(Dispatchers.IO) {
                ResolverRegistry.init(appContext)
            }
            ready = true
        }
    }

    suspend fun await() {
        while (!ready) {
            delay(100)
        }
    }
}
