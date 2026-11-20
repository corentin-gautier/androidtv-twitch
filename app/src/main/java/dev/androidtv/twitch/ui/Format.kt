package dev.androidtv.twitch.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** "38.3K viewers", "1 viewer". */
fun formatViewers(count: Int): String = when {
    count >= 1_000_000 -> String.format(Locale.US, "%.1fM viewers", count / 1_000_000f)
    count >= 1_000 -> String.format(Locale.US, "%.1fK viewers", count / 1_000f)
    count == 1 -> "1 viewer"
    else -> "$count viewers"
}

/** "1:02:03" or "2:03". */
fun formatDuration(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val (h, m, s) = Triple(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

private val DateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

/** "Today", "Yesterday", "3 days ago", "Last week", "3 weeks ago", then the date. */
fun formatRelativeDate(epochMs: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(date, Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate())
    return when {
        days <= 0 -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        days < 14 -> "Last week"
        days < 60 -> "${days / 7} weeks ago"
        else -> DateFormat.format(date)
    }
}
