package mint.app.resolution

import android.content.Context
import mint.app.core.model.MediaItem
import mint.app.core.util.Logger
import mint.app.resolution.impl.InstagramResolver
import mint.app.resolution.impl.TikTokResolver
import mint.app.resolution.impl.YtDlpResolver
import java.net.URI

object ResolverRegistry {

    private const val TAG = "ResolverRegistry"

    private val resolvers: List<Resolver> = listOf(InstagramResolver, TikTokResolver, YtDlpResolver)

    fun init(context: Context) {
        resolvers.forEach { resolver -> resolver.initialize(context) }
        Logger.d(TAG, "init: ${resolvers.size} resolvers initialized")
    }

    fun isSupported(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        val supported = when {
            host == "youtu.be" -> true
            host == "youtube-nocookie.com" -> true
            host.endsWith("youtube.com") -> true
            host == "instagr.am" -> true
            host.endsWith("instagram.com") -> true
            host.endsWith("tiktok.com") -> true
            else -> false
        }
        Logger.d(TAG, "isSupported: url=$url host=$host supported=$supported")
        return supported
    }

    suspend fun resolve(url: String): MediaItem {
        if (!isSupported(url)) {
            Logger.w(TAG, "resolve: unsupported link $url")
            throw Exception("Unsupported link. Only YouTube, YouTube Music, Instagram and TikTok are supported.")
        }
        var lastError: Exception? = null
        for (resolver in resolvers) {
            if (!resolver.supports(url)) {
                Logger.d(TAG, "resolve: ${resolver::class.simpleName} does not support $url")
                continue
            }
            Logger.d(TAG, "resolve: trying ${resolver::class.simpleName}")
            lastError = try {
                val result = resolver.resolve(url)
                Logger.d(TAG, "resolve: ${resolver::class.simpleName} succeeded: ${result.title}")
                return result
            } catch (e: LoginRequiredException) {
                Logger.w(TAG, "resolve: ${resolver::class.simpleName} requires login", e)
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "resolve: ${resolver::class.simpleName} failed", e)
                e
            }
        }
        Logger.e(TAG, "resolve: all resolvers failed", lastError)
        throw lastError ?: Exception("No resolver available for this link")
    }
}