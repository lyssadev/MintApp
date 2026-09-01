package mint.app.resolution

import android.content.Context
import mint.app.core.model.MediaItem
import mint.app.resolution.impl.InstagramResolver
import mint.app.resolution.impl.TikTokResolver
import mint.app.resolution.impl.YtDlpResolver
import java.net.URI

object ResolverRegistry {

    private val resolvers: List<Resolver> = listOf(InstagramResolver, TikTokResolver, YtDlpResolver)

    fun init(context: Context) {
        resolvers.forEach { resolver -> resolver.initialize(context) }
    }

    fun isSupported(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        return when {
            host == "youtu.be" -> true
            host == "youtube-nocookie.com" -> true
            host.endsWith("youtube.com") -> true
            host == "instagr.am" -> true
            host.endsWith("instagram.com") -> true
            host.endsWith("tiktok.com") -> true
            else -> false
        }
    }

    suspend fun resolve(url: String): MediaItem {
        if (!isSupported(url)) {
            throw Exception("Unsupported link. Only YouTube, YouTube Music, Instagram and TikTok are supported.")
        }
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
