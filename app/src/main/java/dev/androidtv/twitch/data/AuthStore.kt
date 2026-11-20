package dev.androidtv.twitch.data

import android.content.Context

/** Persists the Twitch session and the few user preferences. */
class AuthStore(context: Context) {
    private val prefs = context.getSharedPreferences("twitch", Context.MODE_PRIVATE)

    val accessToken: String? get() = prefs.getString(KEY_ACCESS, null)
    val refreshToken: String? get() = prefs.getString(KEY_REFRESH, null)

    fun save(tokens: Tokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken ?: refreshToken)
            .apply()
    }

    /** Signs out. Preferences are kept. */
    fun clear() {
        prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).apply()
    }

    /** Preferred video height, or null for automatic quality. */
    var preferredHeight: Int?
        get() = prefs.getInt(KEY_HEIGHT, 0).takeIf { it > 0 }
        set(value) = prefs.edit().putInt(KEY_HEIGHT, value ?: 0).apply()

    /**
     * Broadcast language used for suggestions and categories, as a Twitch language code ("FR"),
     * or null for every language. French by default.
     */
    var contentLanguage: String?
        get() = prefs.getString(KEY_LANGUAGE, "FR")?.ifEmpty { null }
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value.orEmpty()).apply()

    /** Whether the player shows the chat beside the video. */
    var chatVisible: Boolean
        get() = prefs.getBoolean(KEY_CHAT, false)
        set(value) = prefs.edit().putBoolean(KEY_CHAT, value).apply()

    private companion object {
        const val KEY_CHAT = "chat_visible"
        const val KEY_LANGUAGE = "content_language"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_HEIGHT = "preferred_height"
    }
}
