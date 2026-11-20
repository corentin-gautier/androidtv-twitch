package dev.androidtv.twitch.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.ByteArrayInputStream

/**
 * Reads every playlist the player loads and calls [onAds] when one contains stitched ads.
 * [onAds] runs on a loader thread.
 */
@UnstableApi
class AdDetectingPlaylistParserFactory(private val onAds: () -> Unit) : HlsPlaylistParserFactory {
    private val delegate = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        wrap(delegate.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        wrap(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun wrap(parser: ParsingLoadable.Parser<HlsPlaylist>) =
        ParsingLoadable.Parser<HlsPlaylist> { uri, input ->
            val bytes = input.readBytes()
            if (TwitchPlaylists.hasAds(String(bytes))) onAds()
            parser.parse(uri, ByteArrayInputStream(bytes))
        }
}
