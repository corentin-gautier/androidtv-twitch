package dev.androidtv.twitch.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class FormatTest {
    private val now = Instant.parse("2026-11-20T12:00:00Z").toEpochMilli()

    private fun relative(date: String) = formatRelativeDate(Instant.parse(date).toEpochMilli(), now, ZoneOffset.UTC)

    @Test
    fun `relative dates`() {
        assertEquals("Today", relative("2026-11-20T01:00:00Z"))
        assertEquals("Yesterday", relative("2026-11-19T23:00:00Z"))
        assertEquals("3 days ago", relative("2026-11-17T12:00:00Z"))
        assertEquals("Last week", relative("2026-11-10T12:00:00Z"))
        assertEquals("3 weeks ago", relative("2026-10-30T12:00:00Z"))
        assertEquals("5 Mar 2026", relative("2026-03-05T12:00:00Z"))
    }

    @Test
    fun durations() {
        assertEquals("0:07", formatDuration(7))
        assertEquals("2:03", formatDuration(123))
        assertEquals("1:02:03", formatDuration(3723))
    }

    @Test
    fun `viewer counts`() {
        assertEquals("1 viewer", formatViewers(1))
        assertEquals("320 viewers", formatViewers(320))
        assertEquals("38.3K viewers", formatViewers(38_300))
    }
}
