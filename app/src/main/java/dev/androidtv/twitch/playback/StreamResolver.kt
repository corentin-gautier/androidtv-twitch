package dev.androidtv.twitch.playback

import dev.androidtv.twitch.data.HttpException
import dev.androidtv.twitch.data.TwitchApi
import dev.androidtv.twitch.data.Video
import dev.androidtv.twitch.data.UnauthorizedException
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import kotlin.random.Random

class StreamOfflineException : IOException("Channel is offline")

/** A video exists but cannot be played (restricted with no reachable segments). */
class VideoUnavailableException : IOException("Video is not available")

/**
 * A playable stream. [masterPlaylist] is the multivariant playlist text: it is handed to the
 * player as-is because fetching it again would open a new Twitch session, with its own ads.
 */
class ResolvedStream(val masterPlaylist: String, val sourceIndex: Int, val hasAds: Boolean)

/**
 * Finds an ad-free playlist for a channel.
 *
 * Twitch decides whether to stitch ads from the player the access token was issued for, so the
 * resolver asks for a token as each of [SOURCES] in turn and keeps the first stream without ads.
 */
class StreamResolver(private val api: TwitchApi) {

    /**
     * @param skip index of a source to leave out, used when that source just started showing ads.
     * @return the first ad-free stream, or the first one with ads when every source has them.
     */
    suspend fun resolve(login: String, skip: Int? = null): ResolvedStream {
        var withAds: ResolvedStream? = null
        var failure: IOException? = null
        for ((index, source) in SOURCES.withIndex()) {
            if (index == skip) continue
            try {
                val master = fetchMaster(login, source)
                val variantUrl = TwitchPlaylists.firstVariantUrl(master) ?: throw IOException("Empty playlist")
                val hasAds = TwitchPlaylists.hasAds(api.fetchText(variantUrl))
                val stream = ResolvedStream(master, index, hasAds)
                if (!hasAds) return stream
                if (withAds == null) withAds = stream
            } catch (e: StreamOfflineException) {
                throw e
            } catch (e: UnauthorizedException) {
                throw e
            } catch (e: IOException) {
                failure = e
            }
        }
        return withAds ?: throw failure ?: IOException("No stream source available")
    }

    /**
     * Resolves a past broadcast. Past broadcasts carry no stitched ads, so a single source is enough.
     *
     * A sub-only VOD's token is refused by the playlist server (HTTP 403), but its segments stay
     * public on the CDN. In that case the playlist is rebuilt from the storage path carried by the
     * VOD's thumbnail, the same way the TwitchNoSub extension does it.
     */
    suspend fun resolveVideo(video: Video): ResolvedStream {
        val token = api.vodPlaybackToken(video.id)
        val master = try {
            api.fetchText(playlistUrl("vod/${video.id}", token.value, token.signature))
        } catch (e: HttpException) {
            if (e.code != 403) throw e
            val base = TwitchVods.storageBase(video.seekPreviewsUrl, video.previewUrl) ?: throw VideoUnavailableException()
            TwitchVods.restrictedMaster(base, token.restrictedBitrates)
        }
        return ResolvedStream(master, sourceIndex = 0, hasAds = false)
    }

    private suspend fun fetchMaster(login: String, source: Source): String {
        val (token, signature) = api.playbackToken(login, source.platform, source.playerType)
        return try {
            api.fetchText(playlistUrl("api/channel/hls/$login", token, signature))
        } catch (e: HttpException) {
            if (e.code == 404) throw StreamOfflineException() else throw e
        }
    }

    private fun playlistUrl(path: String, token: String, signature: String): String =
        "https://usher.ttvnw.net/$path.m3u8".toHttpUrl().newBuilder()
            .addQueryParameter("sig", signature)
            .addQueryParameter("token", token)
            .addQueryParameter("allow_source", "true")
            .addQueryParameter("allow_audio_only", "false")
            .addQueryParameter("fast_bread", "true")
            .addQueryParameter("p", Random.nextInt(9_999_999).toString())
            .addQueryParameter("player_backend", "mediaplayer")
            .addQueryParameter("playlist_include_framerate", "true")
            .addQueryParameter("supported_codecs", "h265,h264")
            .build()
            .toString()

    private class Source(val platform: String, val playerType: String)

    private companion object {
        /** In order of preference. The last one is a low-resolution stream, kept as a last resort. */
        val SOURCES = listOf(
            Source("web", "embed"),
            Source("web", "popout"),
            Source("android", "site"),
            Source("web", "frontpage"),
            Source("web", "autoplay"),
        )
    }
}
