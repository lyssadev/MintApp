package mint.app.resolution

import android.content.Context
import mint.app.core.model.MediaItem
import mint.app.resolution.impl.InstagramResolver
import mint.app.resolution.impl.YtDlpResolver

object ResolverRegistry {

    private val resolvers: List<Resolver> = listOf(InstagramResolver, YtDlpResolver)

    fun init(context: Context) {
        resolvers.forEach { resolver -> resolver.initialize(context) }
    }

    suspend fun resolve(url: String): MediaItem {
        var lastError: Exception? = null
        for (resolver in resolvers) {
            if (!resolver.supports(url)) continue
            lastError = try {
                return resolver.resolve(url)
            } catch (e: LoginRequiredException) {
                throw e
            } catch (e: Exception) {
                e
            }
        }
        throw lastError ?: Exception("No resolver available for this link")
    }
}
