package dev.androidtv.twitch.playback

/**
 * Rebuilds the HLS playlist of a sub-only past broadcast from its public CDN storage, so it plays
 * without the access token the playlist server refuses. Same approach as the TwitchNoSub extension.
 */
object TwitchVods {

    /**
     * The CDN base that holds a VOD's segments, as `https://<domain>.cloudfront.net/<path>`, derived
     * from the storyboard URL, or else the thumbnail URL; null when neither carries the storage path.
     *
     * Storyboard: `https://<domain>.cloudfront.net/<path>/storyboards/<id>-info.json`
     * Thumbnail:  `https://static-cdn.jtvnw.net/cf_vods/<domain>/<path>//thumb/thumb0-440x248.jpg`
     */
    fun storageBase(seekPreviewsUrl: String?, thumbnailUrl: String?): String? {
        if (seekPreviewsUrl != null && "/storyboards/" in seekPreviewsUrl) {
            return seekPreviewsUrl.substringBefore("/storyboards/")
        }
        if (thumbnailUrl != null && "/cf_vods/" in thumbnailUrl) {
            val rest = thumbnailUrl.substringAfter("/cf_vods/")
            val domain = rest.substringBefore('/')
            val path = rest.substringAfter('/').substringBefore("/thumb").trimEnd('/')
            // The path always embeds the broadcast id between underscores; a bare word is not one.
            if (domain.isNotEmpty() && '_' in path) return "https://$domain.cloudfront.net/$path"
        }
        return null
    }

    /**
     * A multivariant playlist pointing at the per-quality playlists under [base]. [bitrates] are the
     * quality folder names from the token (e.g. "1080p60", "chunked"); when empty, only the source
     * ("chunked") is offered, which every VOD has.
     */
    fun restrictedMaster(base: String, bitrates: List<String>): String {
        val numbered = bitrates.filter { it != "audio_only" && it != "chunked" }
        // "chunked" is the source and duplicates the top numbered quality, so keep it only alone.
        val qualities = numbered.ifEmpty { listOf("chunked") }
        val variants = qualities.mapNotNull(::quality).sortedByDescending { it.height }.joinToString("\n") {
            "#EXT-X-STREAM-INF:BANDWIDTH=${it.bandwidth},RESOLUTION=${it.width}x${it.height},FRAME-RATE=${it.fps}," +
                "CODECS=\"avc1.4d402a,mp4a.40.2\",VIDEO=\"${it.folder}\"\n$base/${it.folder}/index-dvr.m3u8"
        }
        return "#EXTM3U\n$variants\n"
    }

    private class Quality(val folder: String, val width: Int, val height: Int, val fps: Int, val bandwidth: Int)

    /** Reads a quality folder name into its dimensions; "chunked" (source) is treated as 1080p60. */
    private fun quality(folder: String): Quality? {
        if (folder == "chunked") return Quality(folder, 1920, 1080, 60, 8_000_000)
        val match = Regex("""(\d+)p(\d+)?""").find(folder) ?: return null
        val height = match.groupValues[1].toInt()
        val fps = match.groupValues[2].toIntOrNull() ?: 30
        val width = when (height) {
            1080 -> 1920
            720 -> 1280
            480 -> 852
            360 -> 640
            160 -> 284
            else -> (height * 16 / 9) and 1.inv()
        }
        return Quality(folder, width, height, fps, height * height * 3)
    }
}
