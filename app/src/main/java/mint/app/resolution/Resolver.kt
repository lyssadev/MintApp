package mint.app.resolution

import android.content.Context
import mint.app.core.model.MediaItem

interface Resolver {
    suspend fun resolve(url: String): MediaItem
    fun supports(url: String): Boolean
    fun initialize(context: Context)
}
