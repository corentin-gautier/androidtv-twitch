package dev.androidtv.twitch.data

data class User(val id: String, val login: String, val displayName: String, val avatarUrl: String?)

data class StreamInfo(val title: String, val viewers: Int, val previewUrl: String?, val game: String?)

/** A channel; [stream] is null when it is offline. */
data class Channel(
    val login: String,
    val displayName: String,
    val avatarUrl: String?,
    val stream: StreamInfo?,
)

/** A past broadcast. [publishedAt] is in epoch milliseconds, 0 when unknown. */
data class Video(
    val id: String,
    val title: String,
    val lengthSeconds: Int,
    val publishedAt: Long,
    val previewUrl: String?,
    /** URL of the VOD's storyboard JSON; its path locates the video's segments on the CDN. */
    val seekPreviewsUrl: String?,
    /** Where Twitch last saw the user in this VOD, from whichever device reported it, in seconds; 0 if never. */
    val resumeSeconds: Int,
    /** When that position was reported, in epoch milliseconds; 0 if never. */
    val resumeUpdatedAt: Long,
    val game: String?,
)

/** A past broadcast the user has started, with its channel. [updatedAt] is when it was last watched, in epoch milliseconds. */
data class WatchedVideo(val channel: Channel, val video: Video, val updatedAt: Long)

/** A game or other category streams are filed under. [name] identifies it in queries. */
data class Category(val name: String, val displayName: String, val viewers: Int, val boxArtUrl: String?)

/** What the player can be asked to play. */
sealed interface Playable {
    val channel: Channel

    data class Live(override val channel: Channel) : Playable
    data class Vod(override val channel: Channel, val video: Video) : Playable
}

data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSec: Int,
    val intervalSec: Int,
)

data class Tokens(val accessToken: String, val refreshToken: String?)

/**
 * A playback access token for a stream or a VOD. [restrictedBitrates] is non-empty only for a
 * sub-only VOD, naming the qualities the token server refuses but the CDN still serves.
 */
data class PlaybackToken(val value: String, val signature: String, val restrictedBitrates: List<String> = emptyList())
