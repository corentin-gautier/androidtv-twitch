package dev.androidtv.twitch.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchVodsTest {

    @Test
    fun `storage base from storyboard url`() {
        val base = TwitchVods.storageBase(
            "https://d3stzm2eumvgb4.cloudfront.net/8f50fc74_mistermv_318400694500_1795098469/storyboards/2933720922-info.json",
            null,
        )
        assertEquals("https://d3stzm2eumvgb4.cloudfront.net/8f50fc74_mistermv_318400694500_1795098469", base)
    }

    @Test
    fun `storage base from thumbnail url`() {
        val base = TwitchVods.storageBase(
            null,
            "https://static-cdn.jtvnw.net/cf_vods/d3stzm2eumvgb4/8f50fc74_mistermv_318400694500_1795098469//thumb/thumb0-440x248.jpg",
        )
        assertEquals("https://d3stzm2eumvgb4.cloudfront.net/8f50fc74_mistermv_318400694500_1795098469", base)
    }

    @Test
    fun `no storage base from a processing placeholder thumbnail`() {
        assertNull(TwitchVods.storageBase(null, "https://vod-secure.twitch.tv/_404/404_processing_440x248.png"))
        assertNull(TwitchVods.storageBase(null, null))
    }

    @Test
    fun `master lists each numbered quality, highest first, dropping chunked and audio`() {
        val master = TwitchVods.restrictedMaster(
            "https://cdn.example.com/vod",
            listOf("1080p60", "160p30", "480p30", "chunked", "audio_only"),
        )
        val variants = master.lines().filter { it.endsWith("index-dvr.m3u8") }
        assertEquals(
            listOf(
                "https://cdn.example.com/vod/1080p60/index-dvr.m3u8",
                "https://cdn.example.com/vod/480p30/index-dvr.m3u8",
                "https://cdn.example.com/vod/160p30/index-dvr.m3u8",
            ),
            variants,
        )
        assertTrue("RESOLUTION=1920x1080" in master)
        assertTrue("FRAME-RATE=60" in master)
    }

    @Test
    fun `master falls back to the source when no numbered quality is known`() {
        val master = TwitchVods.restrictedMaster("https://cdn.example.com/vod", emptyList())
        assertEquals(listOf("https://cdn.example.com/vod/chunked/index-dvr.m3u8"), master.lines().filter { it.endsWith("index-dvr.m3u8") })
    }
}
