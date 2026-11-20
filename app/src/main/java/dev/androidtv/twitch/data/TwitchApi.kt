package dev.androidtv.twitch.data

import dev.androidtv.twitch.playback.ChatMessage
import dev.androidtv.twitch.playback.TimedChatMessage
import dev.androidtv.twitch.playback.TwitchChat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/** The stored session was rejected by Twitch and could not be refreshed. */
class UnauthorizedException : IOException("Twitch session expired")

class HttpException(val code: Int, url: String) : IOException("HTTP $code for $url")

/**
 * Twitch client that signs in as the official TV app (device code on id.twitch.tv). Tokens issued
 * for [CLIENT_ID] are accepted by the private GQL API, needed for search and playback tokens, and
 * by the public Helix API, used for the followed channels.
 */
class TwitchApi(val http: OkHttpClient, private val auth: AuthStore) {

    suspend fun requestDeviceCode(): DeviceCode = io {
        val body = FormBody.Builder().add("client_id", CLIENT_ID).add("scopes", SCOPES).build()
        val json = JSONObject(execute(Request.Builder().url("$ID_URL/device").post(body).build()))
        DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            expiresInSec = json.getInt("expires_in"),
            intervalSec = json.optInt("interval", 5),
        )
    }

    /** Returns the tokens once the user has entered the code, or null while still pending. */
    suspend fun pollToken(deviceCode: String): Tokens? = io {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        http.newCall(Request.Builder().url("$ID_URL/token").post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) return@io parseTokens(text)
            val message = runCatching { JSONObject(text).optString("message") }.getOrDefault("")
            if (message == "authorization_pending" || message == "slow_down") null
            else throw IOException("Sign-in failed: ${message.ifEmpty { "HTTP ${response.code}" }}")
        }
    }

    private suspend fun refresh(): Boolean = io {
        val refreshToken = auth.refreshToken ?: return@io false
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        http.newCall(Request.Builder().url("$ID_URL/token").post(body).build()).execute().use { response ->
            if (!response.isSuccessful) return@io false
            auth.save(parseTokens(response.body?.string().orEmpty()))
            true
        }
    }

    suspend fun currentUser(): User? {
        val user = helix("users").getJSONArray("data").optJSONObject(0) ?: return null
        return User(user.getString("id"), user.getString("login"), user.getString("display_name"), user.stringOrNull("profile_image_url"))
    }

    /** Live channels [userId] follows, most watched first. */
    suspend fun followedLive(userId: String): List<Channel> {
        val streams = helix("streams/followed?user_id=$userId&first=100").getJSONArray("data").objects()
        if (streams.isEmpty()) return emptyList()
        val avatars = avatars(streams.map { it.getString("user_id") })
        return streams.map {
            Channel(
                login = it.getString("user_login"),
                displayName = it.getString("user_name"),
                avatarUrl = avatars[it.getString("user_id")],
                stream = StreamInfo(
                    title = it.stringOrNull("title").orEmpty(),
                    viewers = it.optInt("viewer_count"),
                    previewUrl = it.stringOrNull("thumbnail_url")?.replace("{width}x{height}", "440x248")?.let(::refreshed),
                    game = it.stringOrNull("game_name")?.ifEmpty { null },
                ),
            )
        }.sortedByDescending { it.stream?.viewers }
    }

    /** Every channel [userId] follows (up to [MAX_FOLLOWS]), without live details, sorted by name. */
    suspend fun followedChannels(userId: String): List<Channel> {
        val follows = mutableListOf<JSONObject>()
        var cursor: String? = null
        do {
            val page = helix("channels/followed?user_id=$userId&first=100" + (cursor?.let { "&after=$it" } ?: ""))
            follows += page.getJSONArray("data").objects()
            cursor = page.optJSONObject("pagination")?.stringOrNull("cursor")
        } while (cursor != null && follows.size < MAX_FOLLOWS)
        val avatars = avatars(follows.map { it.getString("broadcaster_id") })
        return follows.map {
            Channel(
                login = it.getString("broadcaster_login"),
                displayName = it.getString("broadcaster_name"),
                avatarUrl = avatars[it.getString("broadcaster_id")],
                stream = null,
            )
        }.sortedBy { it.displayName.lowercase() }
    }

    /** Helix lists streams and follows without the channel avatar: this fetches them, 100 users per call. */
    private suspend fun avatars(userIds: List<String>): Map<String, String?> =
        userIds.chunked(100).flatMap { ids ->
            helix("users?" + ids.joinToString("&") { "id=$it" }).getJSONArray("data").objects()
        }.associate { it.getString("id") to it.stringOrNull("profile_image_url") }

    /** A channel and its latest past broadcasts, or null if the channel does not exist. */
    suspend fun channelWithVideos(login: String): Pair<Channel, List<Video>>? {
        val data = gql(
            "query(\$login: String!) { user(login: \$login) { $CHANNEL_FIELDS videos(first: 30, type: ARCHIVE, sort: TIME) " +
                "{ edges { node { $VIDEO_FIELDS } } } } }",
            JSONObject().put("login", login),
        )
        val user = data.optJSONObject("user") ?: return null
        val videos = user.optJSONObject("videos")?.optJSONArray("edges")?.objects().orEmpty()
            .mapNotNull { it.optJSONObject("node") }
            .map(::parseVideo)
        return parseChannel(user) to videos
    }

    /** Past broadcasts the user has started on any device, as Twitch remembers them. */
    suspend fun viewedVideos(): List<WatchedVideo> {
        val data = gql(
            "query { currentUser { viewedVideos(first: 30) { edges { node { $VIDEO_FIELDS " +
                "owner { login displayName profileImageURL(width: 70) } } } } } }",
        )
        val edges = data.optJSONObject("currentUser")?.optJSONObject("viewedVideos")?.optJSONArray("edges")?.objects().orEmpty()
        return edges.mapNotNull { it.optJSONObject("node") }.mapNotNull { node ->
            val owner = node.optJSONObject("owner") ?: return@mapNotNull null
            val updatedAt = node.optJSONObject("self")?.optJSONObject("viewingHistory")?.stringOrNull("updatedAt")?.let(::epochMs) ?: 0
            val channel = Channel(
                login = owner.getString("login"),
                displayName = owner.stringOrNull("displayName") ?: owner.getString("login"),
                avatarUrl = owner.stringOrNull("profileImageURL"),
                stream = null,
            )
            WatchedVideo(channel, parseVideo(node), updatedAt)
        }
    }

    /**
     * The most watched live channels, overall or in the category named [game].
     * [language] is a Twitch language code such as "FR", or null for every language.
     */
    suspend fun topStreams(language: String?, game: String? = null): List<Channel> {
        val options = if (language != null && LANGUAGE_CODE.matches(language)) ", options: {broadcasterLanguages: [$language]}" else ""
        val streams = "streams(first: 24$options) { edges { node { $STREAM_FIELDS } } }"
        val parent = if (game == null) {
            gql("query { $streams }")
        } else {
            gql("query(\$name: String!) { game(name: \$name) { $streams } }", JSONObject().put("name", game)).optJSONObject("game")
                ?: return emptyList()
        }
        return parent.optJSONObject("streams")?.optJSONArray("edges")?.objects().orEmpty()
            .mapNotNull { it.optJSONObject("node")?.let(::parseStream) }
    }

    /** The categories with the most viewers right now. */
    suspend fun topCategories(): List<Category> {
        val data = gql("query { games(first: 42) { edges { node { name displayName viewersCount boxArtURL(width: 285, height: 380) } } } }")
        return data.optJSONObject("games")?.optJSONArray("edges")?.objects().orEmpty()
            .mapNotNull { it.optJSONObject("node") }
            .map {
                Category(
                    name = it.getString("name"),
                    displayName = it.stringOrNull("displayName") ?: it.getString("name"),
                    viewers = it.optInt("viewersCount"),
                    boxArtUrl = it.stringOrNull("boxArtURL"),
                )
            }
    }

    suspend fun search(query: String): List<Channel> {
        val data = gql(
            "query(\$q: String!) { searchFor(userQuery: \$q, platform: \"web\", options: {targets: [{index: CHANNEL}]}) " +
                "{ channels { edges { item { ... on User { $CHANNEL_FIELDS } } } } } }",
            JSONObject().put("q", query),
        )
        val edges = data.optJSONObject("searchFor")?.optJSONObject("channels")?.optJSONArray("edges")
            ?: return emptyList()
        return (0 until edges.length())
            .mapNotNull { edges.getJSONObject(it).optJSONObject("item") }
            .filter { it.has("login") }
            .map(::parseChannel)
    }

    suspend fun channel(login: String): Channel? {
        val data = gql("query(\$login: String!) { user(login: \$login) { $CHANNEL_FIELDS } }", JSONObject().put("login", login))
        return data.optJSONObject("user")?.let(::parseChannel)
    }

    /** Returns the (token, signature) pair the playlist server requires for [login]. */
    suspend fun playbackToken(login: String, platform: String, playerType: String): Pair<String, String> {
        val data = gql(
            "query(\$login: String!, \$platform: String!, \$playerType: String!) { streamPlaybackAccessToken(" +
                "channelName: \$login, params: {platform: \$platform, playerBackend: \"mediaplayer\", playerType: \$playerType}) " +
                "{ value signature } }",
            JSONObject().put("login", login).put("platform", platform).put("playerType", playerType),
        )
        val token = data.optJSONObject("streamPlaybackAccessToken") ?: throw IOException("No playback token for $login")
        return token.getString("value") to token.getString("signature")
    }

    /** The playback token for a past broadcast, including the sub-only quality list when there is one. */
    suspend fun vodPlaybackToken(videoId: String): PlaybackToken {
        val data = gql(
            "query(\$id: ID!) { videoPlaybackAccessToken(id: \$id, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \"embed\"}) " +
                "{ value signature } }",
            JSONObject().put("id", videoId),
        )
        val token = data.optJSONObject("videoPlaybackAccessToken") ?: throw IOException("No playback token for video $videoId")
        val value = token.getString("value")
        // A sub-only VOD's token lists the qualities under chansub.restricted_bitrates.
        val bitrates = runCatching {
            JSONObject(value).optJSONObject("chansub")?.optJSONArray("restricted_bitrates")?.let { arr ->
                (0 until arr.length()).map(arr::getString)
            }
        }.getOrNull().orEmpty()
        return PlaybackToken(value, token.getString("signature"), bitrates)
    }

    /** Tells Twitch how far the user is in a past broadcast, so that their other devices resume from there. */
    suspend fun saveVideoProgress(userId: String, videoId: String, positionSeconds: Int) {
        val input = JSONObject().put("userID", userId).put("videoID", videoId).put("position", positionSeconds).put("videoType", "VOD")
        gql(
            "mutation(\$input: UpdateUserViewedVideoInput!) { updateUserViewedVideo(input: \$input) { video { id } } }",
            JSONObject().put("input", input),
        )
    }

    /**
     * A page of the chat replay of a past broadcast, around [offsetSeconds]: about sixty lines,
     * starting a little before the offset. Empty once past the last line.
     */
    suspend fun videoComments(videoId: String, offsetSeconds: Int): List<TimedChatMessage> {
        val data = gql(
            "query(\$id: ID!, \$offset: Int) { video(id: \$id) { comments(contentOffsetSeconds: \$offset) " +
                "{ edges { node { id contentOffsetSeconds commenter { displayName } message { userColor fragments { text } } } } } } }",
            JSONObject().put("id", videoId).put("offset", offsetSeconds),
        )
        val edges = data.optJSONObject("video")?.optJSONObject("comments")?.optJSONArray("edges")?.objects().orEmpty()
        return edges.mapNotNull { it.optJSONObject("node") }.mapNotNull { node ->
            // The commenter is null for deleted accounts.
            val author = node.optJSONObject("commenter")?.stringOrNull("displayName") ?: return@mapNotNull null
            val message = node.optJSONObject("message") ?: return@mapNotNull null
            val text = message.optJSONArray("fragments")?.objects().orEmpty().joinToString("") { it.stringOrNull("text").orEmpty() }.trim()
            if (text.isEmpty()) return@mapNotNull null
            TimedChatMessage(
                node.optInt("contentOffsetSeconds"),
                ChatMessage(node.getString("id"), author, TwitchChat.nameColor(message.stringOrNull("userColor"), author), text),
            )
        }
    }

    suspend fun fetchText(url: String): String = io { execute(Request.Builder().url(url).build()) }

    /** Calls the public Helix API, used for what GQL refuses to this client: the followed channels. */
    private suspend fun helix(path: String): JSONObject {
        val text = authenticated { token ->
            Request.Builder().url("$HELIX_URL/$path").header("Client-Id", CLIENT_ID).header("Authorization", "Bearer $token").build()
        }
        return io { JSONObject(text) }
    }

    private suspend fun gql(query: String, variables: JSONObject? = null): JSONObject {
        val payload = JSONObject().put("query", query)
        if (variables != null) payload.put("variables", variables)
        val text = authenticated { token ->
            Request.Builder()
                .url(GQL_URL)
                .header("Client-Id", CLIENT_ID)
                .header("Authorization", "OAuth $token")
                .post(payload.toString().toRequestBody(JSON))
                .build()
        }
        return io {
            val json = JSONObject(text)
            // GQL answers 200 with a null field and an "errors" entry when part of a query fails.
            val error = json.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
            if (error != null) throw IOException("Twitch error: $error")
            json.optJSONObject("data") ?: throw IOException("Empty Twitch response")
        }
    }

    /** Sends a request built with the access token; on a 401, refreshes the token once and retries. */
    private suspend fun authenticated(request: (token: String) -> Request): String {
        val token = auth.accessToken ?: throw UnauthorizedException()
        try {
            return io { execute(request(token)) }
        } catch (e: HttpException) {
            if (e.code != 401) throw e
        }
        if (!refresh()) throw UnauthorizedException()
        try {
            return io { execute(request(auth.accessToken ?: throw UnauthorizedException())) }
        } catch (e: HttpException) {
            // Still refused with a fresh token: the session lacks a permission and must be recreated.
            throw if (e.code == 401) UnauthorizedException() else e
        }
    }

    private fun execute(request: Request): String =
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, request.url.host)
            response.body?.string().orEmpty()
        }

    private fun parseTokens(text: String): Tokens {
        val json = JSONObject(text)
        return Tokens(json.getString("access_token"), json.stringOrNull("refresh_token"))
    }

    private fun parseVideo(node: JSONObject) = Video(
        id = node.getString("id"),
        title = node.stringOrNull("title").orEmpty(),
        lengthSeconds = node.optInt("lengthSeconds"),
        publishedAt = node.stringOrNull("publishedAt")?.let(::epochMs) ?: 0,
        previewUrl = node.stringOrNull("previewThumbnailURL"),
        seekPreviewsUrl = node.stringOrNull("seekPreviewsURL"),
        resumeSeconds = node.optJSONObject("self")?.optJSONObject("viewingHistory")?.optInt("position") ?: 0,
        resumeUpdatedAt = node.optJSONObject("self")?.optJSONObject("viewingHistory")?.stringOrNull("updatedAt")?.let(::epochMs) ?: 0,
        game = node.optJSONObject("game")?.stringOrNull("displayName"),
    )

    /** Reads a stream node, which carries its channel as "broadcaster"; null when the channel is gone. */
    private fun parseStream(node: JSONObject): Channel? {
        val broadcaster = node.optJSONObject("broadcaster") ?: return null
        return Channel(
            login = broadcaster.getString("login"),
            displayName = broadcaster.stringOrNull("displayName") ?: broadcaster.getString("login"),
            avatarUrl = broadcaster.stringOrNull("profileImageURL"),
            stream = StreamInfo(
                title = node.stringOrNull("title").orEmpty(),
                viewers = node.optInt("viewersCount"),
                previewUrl = node.stringOrNull("previewImageURL")?.let(::refreshed),
                game = node.optJSONObject("game")?.stringOrNull("displayName"),
            ),
        )
    }

    private fun epochMs(isoDate: String): Long? = runCatching { Instant.parse(isoDate).toEpochMilli() }.getOrNull()

    private fun parseChannel(node: JSONObject): Channel {
        val stream = node.optJSONObject("stream")?.let {
            StreamInfo(
                title = it.stringOrNull("title").orEmpty(),
                viewers = it.optInt("viewersCount"),
                previewUrl = it.stringOrNull("previewImageURL")?.let(::refreshed),
                game = it.optJSONObject("game")?.stringOrNull("displayName"),
            )
        }
        return Channel(
            login = node.getString("login"),
            displayName = node.stringOrNull("displayName") ?: node.getString("login"),
            avatarUrl = node.stringOrNull("profileImageURL"),
            stream = stream,
        )
    }

    /** Twitch reuses the same preview URL for a channel: the suffix renews it every 5 minutes. */
    private fun refreshed(url: String) = "$url?t=${System.currentTimeMillis() / 300_000}"

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    /** Runs a blocking call on the IO dispatcher and reports malformed JSON as an I/O failure. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: JSONException) {
            throw IOException("Unexpected Twitch response", e)
        }
    }

    private companion object {
        /** Client ID of Twitch's own TV app. */
        const val CLIENT_ID = "ue6666qo983tsx6so1t0vnawi233wa"
        /** user:read:follows is what the Helix followed-channels endpoints require. */
        const val SCOPES = "user:read:follows user_read"
        const val ID_URL = "https://id.twitch.tv/oauth2"
        const val MAX_FOLLOWS = 1000
        const val HELIX_URL = "https://api.twitch.tv/helix"
        const val GQL_URL = "https://gql.twitch.tv/gql"
        const val VIDEO_FIELDS = "id title lengthSeconds publishedAt previewThumbnailURL(width: 440, height: 248) seekPreviewsURL " +
            "self { viewingHistory { position updatedAt } } game { displayName }"
        const val STREAM_FIELDS = "broadcaster { login displayName profileImageURL(width: 70) } " +
            "title viewersCount previewImageURL(width: 440, height: 248) game { displayName }"
        val LANGUAGE_CODE = Regex("[A-Z]{2}")
        const val CHANNEL_FIELDS = "login displayName profileImageURL(width: 70) " +
            "stream { title viewersCount previewImageURL(width: 440, height: 248) game { displayName } }"
        val JSON = "application/json".toMediaType()
    }
}
