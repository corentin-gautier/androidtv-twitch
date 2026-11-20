package dev.androidtv.twitch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressTest {
    /** A one-hour broadcast that Twitch last saw at [resumeSeconds], reported at time [at]. */
    private fun video(resumeSeconds: Int = 0, at: Long = 0) = Video("1", "title", 3600, 0, null, null, resumeSeconds, at, null)

    private fun local(positionMs: Long, at: Long) = LocalProgress(positionMs, at)

    @Test
    fun `the most recent record wins, not the furthest`() {
        // Watched further here, then rewound on another device.
        assertEquals(600_000, WatchProgress.watchedMs(local(1_800_000, at = 100), video(resumeSeconds = 600, at = 200)))
        // Watched on another device, then here.
        assertEquals(1_800_000, WatchProgress.watchedMs(local(1_800_000, at = 300), video(resumeSeconds = 600, at = 200)))
    }

    @Test
    fun `a single record is used as it is`() {
        assertEquals(1_200_000, WatchProgress.resumeMs(null, video(resumeSeconds = 1200, at = 200)))
        assertEquals(5_000, WatchProgress.resumeMs(local(5_000, at = 100), video()))
        assertEquals(0, WatchProgress.resumeMs(null, video()))
    }

    @Test
    fun `a broadcast finished here restarts from the beginning`() {
        val video = video(resumeSeconds = 1200, at = 100)
        val finished = local(WatchProgress.FINISHED, at = 200)
        assertEquals(0, WatchProgress.resumeMs(finished, video))
        assertFalse(WatchProgress.isInProgress(finished, video))
        assertEquals(1f, WatchProgress.fraction(finished, video), 0f)
    }

    @Test
    fun `a broadcast finished here and started again elsewhere is in progress again`() {
        val video = video(resumeSeconds = 1200, at = 300)
        assertTrue(WatchProgress.isInProgress(local(WatchProgress.FINISHED, at = 200), video))
    }

    @Test
    fun `stopping within the last minute counts as finished`() {
        val video = video(resumeSeconds = 3570, at = 100)
        assertEquals(0, WatchProgress.resumeMs(null, video))
        assertFalse(WatchProgress.isInProgress(null, video))
    }

    @Test
    fun `in progress means started and not finished`() {
        assertFalse(WatchProgress.isInProgress(null, video()))
        assertTrue(WatchProgress.isInProgress(null, video(resumeSeconds = 1200, at = 100)))
        assertTrue(WatchProgress.isInProgress(local(5_000, at = 100), video()))
        assertEquals(0.5f, WatchProgress.fraction(local(1_800_000, at = 100), video()), 0f)
    }
}
