package dev.androidtv.twitch.playback

/** Pure helpers for the HLS playlists Twitch serves. */
object TwitchPlaylists {

    /** Twitch stitches ads into the media playlist and tags them with a "stitched-ad" date range. */
    fun hasAds(mediaPlaylist: String): Boolean = "stitched-ad" in mediaPlaylist

    /** URL of the first (best) variant of a multivariant playlist, or null if it lists none. */
    fun firstVariantUrl(multivariantPlaylist: String): String? {
        val lines = multivariantPlaylist.lineSequence().map { it.trim() }.toList()
        val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        if (index < 0) return null
        return lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
    }
}
