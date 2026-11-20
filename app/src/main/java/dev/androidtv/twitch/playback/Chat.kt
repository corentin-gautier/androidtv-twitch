package dev.androidtv.twitch.playback

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import dev.androidtv.twitch.data.TwitchApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException

/** The chat lines on screen, oldest first. Main thread only. */
class ChatFeed {
    /** A line with a key unique within the feed, as Twitch ids can repeat or be missing. */
    class Line(val key: Long, val message: ChatMessage)

    val lines = mutableStateListOf<Line>()
    private var nextKey = 0L

    fun add(message: ChatMessage) {
        lines.add(Line(nextKey++, message))
        if (lines.size > MAX_LINES) lines.removeRange(0, lines.size - MAX_LINES)
    }

    fun clear() = lines.clear()

    private companion object {
        const val MAX_LINES = 150
    }
}

/**
 * Feeds [feed] with the live chat of [login] until cancelled. Reads Twitch's IRC over WebSocket
 * with an anonymous, read-only login, and reconnects when the connection drops.
 */
suspend fun liveChat(http: OkHttpClient, login: String, feed: ChatFeed) {
    val mainHandler = Handler(Looper.getMainLooper())
    var active = true
    try {
        while (true) {
            val closed = CompletableDeferred<Unit>()
            val socket = http.newWebSocket(
                Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                        webSocket.send("PASS SCHMOOPIIE")
                        webSocket.send("NICK justinfan${(10_000..99_999).random()}")
                        webSocket.send("JOIN #${login.lowercase()}")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        text.split("\r\n").forEach { line ->
                            if (line.startsWith("PING")) {
                                webSocket.send("PONG" + line.substring(4))
                            } else {
                                val message = TwitchChat.parse(line) ?: return@forEach
                                mainHandler.post { if (active) feed.add(message) }
                            }
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closed.complete(Unit)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        closed.complete(Unit)
                    }
                },
            )
            try {
                closed.await()
            } finally {
                socket.cancel()
            }
            delay(RECONNECT_DELAY_MS)
        }
    } finally {
        active = false
    }
}

/**
 * Feeds [feed] with the chat replay of a past broadcast, in step with playback, until cancelled.
 * [positionSeconds] is read on each tick to know where playback is.
 *
 * Twitch serves the replay in pages of about sixty lines around an offset; each page starts a
 * little before the offset asked for, so pages overlap and lines are told apart by their id.
 */
suspend fun replayChat(api: TwitchApi, videoId: String, positionSeconds: () -> Long, feed: ChatFeed) {
    val pending = ArrayDeque<TimedChatMessage>()
    val seen = HashSet<String>()
    var nextOffset = 0
    var exhausted = false
    var lastPosition = -1L
    while (true) {
        val now = positionSeconds()
        // First run, or a jump in either direction: start again from where playback is.
        if (lastPosition < 0 || now < lastPosition - 2 || now > lastPosition + 15) {
            feed.clear()
            pending.clear()
            seen.clear()
            nextOffset = now.toInt()
            exhausted = false
        }
        lastPosition = now

        val bufferedUntil = pending.lastOrNull()?.offsetSeconds ?: -1
        if (!exhausted && bufferedUntil < now + BUFFER_AHEAD_SECONDS) {
            try {
                val page = api.videoComments(videoId, nextOffset)
                val fresh = page.filter { seen.add(it.message.id) }
                when {
                    page.isEmpty() -> exhausted = true
                    // Nothing new around this offset: look further.
                    fresh.isEmpty() -> nextOffset = maxOf(nextOffset, page.last().offsetSeconds) + 1
                    else -> {
                        pending.addAll(fresh)
                        nextOffset = maxOf(nextOffset, page.last().offsetSeconds)
                    }
                }
            } catch (e: IOException) {
                // The replay is a comfort: on a failed page, try again on a later tick.
                delay(RETRY_DELAY_MS)
            }
        }
        while (pending.isNotEmpty() && pending.first().offsetSeconds <= now) {
            feed.add(pending.removeFirst().message)
        }
        delay(TICK_MS)
    }
}

private const val RECONNECT_DELAY_MS = 3_000L
private const val RETRY_DELAY_MS = 5_000L
private const val TICK_MS = 500L
private const val BUFFER_AHEAD_SECONDS = 20
