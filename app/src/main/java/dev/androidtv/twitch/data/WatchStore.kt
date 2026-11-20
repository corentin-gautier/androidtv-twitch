package dev.androidtv.twitch.data

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

/** Where a past broadcast stopped on this device ([positionMs], or [WatchProgress.FINISHED]) and when. */
class LocalProgress(val positionMs: Long, val updatedAt: Long)

/**
 * Pure rules for how far a past broadcast has been watched. Two records exist: the one this device
 * keeps, and the one Twitch keeps, fed by every device including this one. They can disagree (a
 * report that failed, a video list fetched before watching), and then the most recent one is right.
 */
object WatchProgress {
    /** Local position marking a broadcast watched to the end on this device. */
    const val FINISHED = -1L

    /** A broadcast stopped this close to its end counts as watched. */
    const val FINISHED_MARGIN_MS = 60_000L

    /** How far the broadcast was watched, in milliseconds, according to whichever record is the most recent. */
    fun watchedMs(local: LocalProgress?, video: Video): Long {
        val duration = video.lengthSeconds * 1000L
        val remote = video.resumeSeconds * 1000L
        val watched = when {
            local == null || (remote > 0 && video.resumeUpdatedAt > local.updatedAt) -> remote
            local.positionMs == FINISHED -> duration
            else -> local.positionMs
        }
        return watched.coerceIn(0, maxOf(duration, 0))
    }

    fun isFinished(watchedMs: Long, video: Video): Boolean {
        val duration = video.lengthSeconds * 1000L
        return duration > 0 && duration - watchedMs < FINISHED_MARGIN_MS
    }

    /** Started and not finished: what "continue watching" lists. */
    fun isInProgress(local: LocalProgress?, video: Video): Boolean {
        val watched = watchedMs(local, video)
        return watched > 0 && !isFinished(watched, video)
    }

    /** Where playback should start: where it stopped, or the beginning once the broadcast was finished. */
    fun resumeMs(local: LocalProgress?, video: Video): Long {
        val watched = watchedMs(local, video)
        return if (isFinished(watched, video)) 0 else watched
    }

    /** Watched share of the broadcast, from 0 to 1. */
    fun fraction(local: LocalProgress?, video: Video): Float {
        val duration = video.lengthSeconds * 1000L
        return if (duration > 0) (watchedMs(local, video).toFloat() / duration).coerceIn(0f, 1f) else 0f
    }
}

/**
 * Remembers the past broadcasts watched on this device and where each stopped. Progress is also
 * reported to Twitch, but this record stays the reference when a report fails. Main thread only.
 */
class WatchStore(context: Context) {
    private val prefs = context.getSharedPreferences("watch", Context.MODE_PRIVATE)
    private val entries = load()

    private class Entry(val watched: WatchedVideo, val positionMs: Long)

    /** Where a broadcast stopped on this device, or null if it was never watched here. */
    fun local(videoId: String): LocalProgress? = entries[videoId]?.let { LocalProgress(it.positionMs, it.watched.updatedAt) }

    fun save(channel: Channel, video: Video, positionMs: Long) {
        entries[video.id] = Entry(WatchedVideo(channel, video, System.currentTimeMillis()), positionMs)
        // Keep the most recent ones only.
        entries.values.sortedByDescending { it.watched.updatedAt }.drop(MAX_ENTRIES).forEach { entries.remove(it.watched.video.id) }
        val json = JSONObject()
        entries.forEach { (id, entry) -> json.put(id, toJson(entry)) }
        prefs.edit().putString(KEY_ENTRIES, json.toString()).apply()
    }

    /** Every broadcast watched on this device. */
    fun history(): List<WatchedVideo> = entries.values.map { it.watched }

    private fun load(): MutableMap<String, Entry> {
        val result = mutableMapOf<String, Entry>()
        try {
            val json = JSONObject(prefs.getString(KEY_ENTRIES, null) ?: return result)
            json.keys().forEach { id -> result[id] = fromJson(id, json.getJSONObject(id)) }
        } catch (e: JSONException) {
            // An unreadable history is dropped: positions restart from what Twitch knows.
            result.clear()
        }
        return result
    }

    private fun toJson(entry: Entry): JSONObject {
        val video = entry.watched.video
        val channel = entry.watched.channel
        return JSONObject()
            .put("position", entry.positionMs)
            .put("updatedAt", entry.watched.updatedAt)
            .put("title", video.title)
            .put("length", video.lengthSeconds)
            .put("publishedAt", video.publishedAt)
            .putOpt("preview", video.previewUrl)
            .putOpt("seekPreviews", video.seekPreviewsUrl)
            .putOpt("game", video.game)
            .put("login", channel.login)
            .put("name", channel.displayName)
            .putOpt("avatar", channel.avatarUrl)
    }

    private fun fromJson(id: String, json: JSONObject): Entry {
        fun text(key: String) = if (json.isNull(key)) null else json.getString(key)
        val video = Video(
            id = id,
            title = json.getString("title"),
            lengthSeconds = json.getInt("length"),
            publishedAt = json.getLong("publishedAt"),
            previewUrl = text("preview"),
            seekPreviewsUrl = text("seekPreviews"),
            resumeSeconds = 0,
            resumeUpdatedAt = 0,
            game = text("game"),
        )
        val channel = Channel(json.getString("login"), json.getString("name"), text("avatar"), stream = null)
        return Entry(WatchedVideo(channel, video, json.getLong("updatedAt")), json.getLong("position"))
    }

    private companion object {
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 100
    }
}
