package dev.androidtv.twitch.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchPlaylistsTest {

    @Test
    fun `detects a stitched ad`() {
        val playlist = """
            #EXTM3U
            #EXT-X-DATERANGE:ID="stitched-ad-1795174272-30235000000",CLASS="twitch-stitched-ad",DURATION=30.235
            #EXTINF:2.000,Amazon|2474283100495.0
            https://example.com/ad.mp4
        """.trimIndent()
        assertTrue(TwitchPlaylists.hasAds(playlist))
    }

    @Test
    fun `a live playlist has no ads`() {
        val playlist = """
            #EXTM3U
            #EXT-X-DATERANGE:ID="source-1",CLASS="twitch-stream-source",X-TV-TWITCH-STREAM-SOURCE="live"
            #EXTINF:2.000,live
            https://example.com/1.ts
        """.trimIndent()
        assertFalse(TwitchPlaylists.hasAds(playlist))
    }

    @Test
    fun `returns the first variant url`() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60 (source)"
            #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080

            https://example.com/chunked.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720
            https://example.com/720.m3u8
        """.trimIndent()
        assertEquals("https://example.com/chunked.m3u8", TwitchPlaylists.firstVariantUrl(master))
    }

    @Test
    fun `no variant in an empty playlist`() {
        assertNull(TwitchPlaylists.firstVariantUrl("#EXTM3U"))
    }
}
