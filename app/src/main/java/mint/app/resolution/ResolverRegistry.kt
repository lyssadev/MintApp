package mint.app.resolution

import android.content.Context
import mint.app.core.model.MediaItem
import mint.app.resolution.impl.YtDlpResolver

object ResolverRegistry {

    private val resolvers: List<Resolver> = listOf(YtDlpResolver)

    fun init(context: Context) {
        resolvers.forEach { resolver ->
            if (resolver is YtDlpResolver) resolver.initialize(context)
        }
    }

    suspend fun resolve(url: String): MediaItem {
        val resolver = resolvers.firstOrNull { it.supports(url) } ?: YtDlpResolver
        return resolver.resolve(url)
    }
}
