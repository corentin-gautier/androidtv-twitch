package dev.androidtv.twitch.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchChatTest {

    @Test
    fun `reads a chat line`() {
        val message = TwitchChat.parse(
            "@badge-info=;color=#1E90FF;display-name=Eddy_693;emotes=;id=5e58;user-type= " +
                ":eddy_693!eddy_693@eddy_693.tmi.twitch.tv PRIVMSG #jltomy :hello : there",
        )
        assertEquals(ChatMessage("5e58", "Eddy_693", 0x1E90FF, "hello : there"), message)
    }

    @Test
    fun `falls back to the login when there is no display name`() {
        val message = TwitchChat.parse("@color=;display-name=;id=1 :someone!someone@someone.tmi.twitch.tv PRIVMSG #chan :hi")
        assertEquals("someone", message?.author)
    }

    @Test
    fun `unwraps a me message`() {
        val message = TwitchChat.parse("@id=1;display-name=A :a!a@a.tmi.twitch.tv PRIVMSG #chan :\u0001ACTION waves\u0001")
        assertEquals("waves", message?.text)
    }

    @Test
    fun `ignores lines that are not chat messages`() {
        assertNull(TwitchChat.parse("PING :tmi.twitch.tv"))
        assertNull(TwitchChat.parse(":justinfan1!justinfan1@justinfan1.tmi.twitch.tv JOIN #chan"))
    }

    @Test
    fun `dark name colours are lightened, light ones kept`() {
        assertEquals(0xFFFF00, TwitchChat.readable(0xFFFF00))
        val blue = TwitchChat.readable(0x0000FF)
        val brightness = 0.299 * (blue shr 16 and 0xFF) + 0.587 * (blue shr 8 and 0xFF) + 0.114 * (blue and 0xFF)
        assertTrue(brightness >= 110)
        // Still recognisably blue.
        assertTrue((blue and 0xFF) > (blue shr 16 and 0xFF))
    }

    @Test
    fun `a user without a colour always gets the same one`() {
        assertEquals(TwitchChat.nameColor(null, "Someone"), TwitchChat.nameColor("", "someone"))
    }
}
