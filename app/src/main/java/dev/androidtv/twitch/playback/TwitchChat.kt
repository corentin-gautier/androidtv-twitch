package dev.androidtv.twitch.playback

/** One chat line. [color] is the author's name colour as 0xRRGGBB, already readable on a dark background. */
data class ChatMessage(val id: String, val author: String, val color: Int, val text: String)

/** A chat line of a past broadcast, with the second of the broadcast it was sent at. */
class TimedChatMessage(val offsetSeconds: Int, val message: ChatMessage)

/** Pure helpers for Twitch chat: reading IRC lines and making name colours legible. */
object TwitchChat {

    /**
     * Reads one line of Twitch's IRC feed into a chat message; null for anything that is not one
     * (pings, joins, notices). A line looks like:
     * `@color=#1E90FF;display-name=Name;id=abc :name!name@name.tmi.twitch.tv PRIVMSG #channel :the text`
     */
    fun parse(line: String): ChatMessage? {
        val marker = line.indexOf(" PRIVMSG #")
        if (marker < 0) return null
        val textStart = line.indexOf(" :", marker)
        if (textStart < 0) return null
        val tags = if (line.startsWith("@")) {
            line.substring(1, line.indexOf(' ')).split(';').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        } else {
            emptyMap()
        }
        val prefix = line.substring(0, marker).substringAfterLast(" :")
        val author = tags["display-name"].orEmpty().ifEmpty { prefix.substringBefore('!') }
        // "/me" messages arrive wrapped as \u0001ACTION text\u0001.
        val text = line.substring(textStart + 2).removePrefix("\u0001ACTION ").removeSuffix("\u0001").trim()
        if (author.isEmpty() || text.isEmpty()) return null
        return ChatMessage(tags["id"].orEmpty(), author, nameColor(tags["color"], author), text)
    }

    /** The colour to show [author] in: their own ([hex], "#RRGGBB") or a stable default, lightened if too dark. */
    fun nameColor(hex: String?, author: String): Int {
        val chosen = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16)
        return readable(chosen ?: DEFAULT_COLORS[Math.floorMod(author.lowercase().hashCode(), DEFAULT_COLORS.size)])
    }

    /** Mixes a colour with white until it is light enough to read on the app's dark background. */
    fun readable(rgb: Int): Int {
        var (r, g, b) = Triple(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF)
        // Perceived brightness, 0 to 255.
        while (0.299 * r + 0.587 * g + 0.114 * b < MIN_BRIGHTNESS) {
            r += (255 - r + 2) / 3
            g += (255 - g + 2) / 3
            b += (255 - b + 2) / 3
        }
        return (r shl 16) or (g shl 8) or b
    }

    private const val MIN_BRIGHTNESS = 110

    /** Twitch's default name colours, given to users who never picked one. */
    private val DEFAULT_COLORS = intArrayOf(
        0xFF0000, 0x0000FF, 0x008000, 0xB22222, 0xFF7F50, 0x9ACD32, 0xFF4500, 0x2E8B57,
        0xDAA520, 0xD2691E, 0x5F9EA0, 0x1E90FF, 0xFF69B4, 0x8A2BE2, 0x00FF7F,
    )
}
