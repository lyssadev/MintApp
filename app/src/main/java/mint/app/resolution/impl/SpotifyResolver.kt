package mint.app.resolution.impl

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mint.app.core.model.MediaItem
import mint.app.core.model.PlaylistEntry
import mint.app.core.model.PlaylistResult
import mint.app.core.util.Logger
import mint.app.resolution.Resolver
import mint.app.resolution.spotify.MusicMatcher
import mint.app.resolution.spotify.SpotifyMetadata
import mint.app.resolution.spotify.SpotifyTrack
import mint.app.resolution.spotify.YouTubeMusicSearch

object SpotifyResolver : Resolver {

    private const val TAG = "SpotifyResolver"
    private const val SEARCH_LIMIT = 20
    private const val WATCH_URL = "https://www.youtube.com/watch?v="

    override fun initialize(context: Context) {}

    override fun supports(url: String): Boolean = SpotifyMetadata.isSpotify(url)

    override suspend fun resolve(url: String): MediaItem = withContext(Dispatchers.IO) {
        val parsed = SpotifyMetadata.parseLink(url) ?: throw Exception("Unsupported Spotify link")
        if (parsed.first != "track") throw Exception("Paste a Spotify track, album or playlist link")
        val collection = SpotifyMetadata.fetch(parsed.first, parsed.second)
        val track = collection.tracks.firstOrNull() ?: throw Exception("Could not read this Spotify track")
        val videoId = matchTrack(track) ?: throw Exception("No matching song found on YouTube Music")
        val youtubeUrl = WATCH_URL + videoId
        Logger.d(TAG, "resolve: '${track.title}' -> $videoId")
        val base = YtDlpResolver.resolveLocal(youtubeUrl)
        base.copy(
            originalUrl = youtubeUrl,
            title = track.title,
            uploader = track.artist.ifBlank { base.uploader },
            thumbnailUrl = track.coverUrl ?: base.thumbnailUrl,
            isMusicOnly = true,
            streamType = "AUDIO",
            platform = "spotify",
            videoOptions = emptyList(),
            imageOptions = emptyList(),
            gifOptions = emptyList(),
            directDownload = false,
        )
    }

    suspend fun resolveCollection(url: String, offset: Int, limit: Int): PlaylistResult = withContext(Dispatchers.IO) {
        val parsed = SpotifyMetadata.parseLink(url) ?: throw Exception("Unsupported Spotify link")
        if (parsed.first == "track") throw Exception("This Spotify link is a single track")
        val collection = SpotifyMetadata.fetch(parsed.first, parsed.second)
        val tracks = collection.tracks
        if (tracks.isEmpty()) throw Exception("This Spotify collection has no tracks")
        val start = offset.coerceAtLeast(0)
        val slice = tracks.drop(start).take(limit.coerceAtLeast(1))
        Logger.d(TAG, "resolveCollection: '${collection.title}' offset=$start slice=${slice.size}/${tracks.size}")
        val entries = slice.mapNotNull { track ->
            val videoId = runCatching { matchTrack(track) }.getOrNull() ?: return@mapNotNull null
            PlaylistEntry(
                id = track.id,
                title = track.title,
                url = WATCH_URL + videoId,
                durationText = durationText(track.durationMs),
                thumbnailUrl = track.coverUrl,
                uploader = track.artist,
            )
        }
        PlaylistResult(
            originalUrl = url,
            title = collection.title,
            uploader = collection.owner,
            entries = entries,
            totalCount = tracks.size,
            hasMore = start + slice.size < tracks.size,
        )
    }

    private suspend fun matchTrack(track: SpotifyTrack): String? {
        val query = buildString {
            append(track.title)
            if (track.artist.isNotBlank()) append(' ').append(track.artist)
        }
        val candidates = YouTubeMusicSearch.search(query, SEARCH_LIMIT)
        if (candidates.isEmpty()) {
            Logger.w(TAG, "matchTrack: no candidates for '$query'")
            return null
        }
        val target = MusicMatcher.Target(
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = if (track.durationMs > 0) (track.durationMs / 1000).toInt() else null,
        )
        val match = MusicMatcher.best(candidates, target)
        if (match == null) Logger.w(TAG, "matchTrack: no acceptable match for '$query'")
        return match?.videoId
    }

    private fun durationText(durationMs: Long): String {
        val total = durationMs / 1000
        if (total <= 0) return ""
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
