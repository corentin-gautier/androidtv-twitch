package dev.androidtv.twitch.ui

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.androidtv.twitch.data.AuthStore
import dev.androidtv.twitch.data.Category
import dev.androidtv.twitch.data.Channel
import dev.androidtv.twitch.data.Playable
import dev.androidtv.twitch.data.TwitchApi
import dev.androidtv.twitch.data.UnauthorizedException
import dev.androidtv.twitch.data.User
import dev.androidtv.twitch.data.Video
import dev.androidtv.twitch.data.WatchProgress
import dev.androidtv.twitch.data.WatchStore
import dev.androidtv.twitch.data.WatchedVideo
import dev.androidtv.twitch.playback.StreamResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.IOException

sealed interface Session {
    data object Loading : Session
    data object Unreachable : Session
    data object SignedOut : Session
    data class SignedIn(val user: User) : Session
}

sealed interface SignInState {
    data object Requesting : SignInState
    data class Code(val userCode: String, val qr: ImageBitmap) : SignInState
    data class Failed(val message: String) : SignInState
}

sealed interface ChannelList {
    data object Loading : ChannelList
    data object Failed : ChannelList
    data class Loaded(val channels: List<Channel>) : ChannelList
}

/** The channels the user follows, split by whether they are live. */
sealed interface Following {
    data object Loading : Following
    data object Failed : Following
    data class Loaded(val live: List<Channel>, val offline: List<Channel>) : Following
}

/** The page of one channel: its details and past broadcasts. */
sealed interface ChannelPage {
    val channel: Channel

    data class Loading(override val channel: Channel) : ChannelPage
    data class Failed(override val channel: Channel) : ChannelPage
    data class Loaded(override val channel: Channel, val videos: List<Video>) : ChannelPage
}

/** One row of suggested live channels on the home screen. */
class StreamRow(val title: String, val channels: List<Channel>)

/** What the home screen shows: the user's watch history, their live follows, and suggestions. */
sealed interface HomeFeed {
    data object Loading : HomeFeed
    data object Failed : HomeFeed
    data class Loaded(val viewed: List<WatchedVideo>, val followedLive: List<Channel>, val suggestions: List<StreamRow>) : HomeFeed
}

sealed interface CategoryList {
    data object Loading : CategoryList
    data object Failed : CategoryList
    data class Loaded(val categories: List<Category>) : CategoryList
}

/** The page of one category: its live channels. */
sealed interface CategoryPage {
    val category: Category

    data class Loading(override val category: Category) : CategoryPage
    data class Failed(override val category: Category) : CategoryPage
    data class Loaded(override val category: Category, val channels: List<Channel>) : CategoryPage
}

/** Search has no button: it is entered by submitting the search field of the top bar. */
enum class Tab(val label: String) { Home("Home"), Following("Following"), Browse("Browse"), Search("Search") }

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val store = AuthStore(application)
    val api = TwitchApi(OkHttpClient(), store)
    val resolver = StreamResolver(api)
    val watch = WatchStore(application)

    var session by mutableStateOf<Session>(Session.Loading)
        private set
    var signIn by mutableStateOf<SignInState>(SignInState.Requesting)
        private set
    var tab by mutableStateOf(Tab.Home)
    var home by mutableStateOf<HomeFeed>(HomeFeed.Loading)
        private set
    var categories by mutableStateOf<CategoryList>(CategoryList.Loading)
        private set

    /** Broadcast language of suggestions and categories ("FR"), or null for every language. */
    var contentLanguage by mutableStateOf(store.contentLanguage)
        private set
    var following by mutableStateOf<Following>(Following.Loading)
        private set
    var searchQuery by mutableStateOf("")

    /** Null until a search has been run. */
    var searchResults by mutableStateOf<ChannelList?>(null)
        private set

    /**
     * Demo mode, for screenshots: the Following tab shows a placeholder account following channels
     * drawn at random from the most watched ones, instead of the user's own. Other tabs are unchanged.
     */
    var demo by mutableStateOf(false)
        private set

    /** The category page shown over the home screen, or null. */
    var categoryPage by mutableStateOf<CategoryPage?>(null)
        private set

    /** The channel page shown over the home screen and the category page, or null. */
    var channelPage by mutableStateOf<ChannelPage?>(null)
        private set

    /** What is being watched, or null when browsing. It shows over every other screen. */
    var playing by mutableStateOf<Playable?>(null)

    /** Login of the last channel opened or watched, so the lists can give it the focus back. */
    var lastChannel by mutableStateOf<String?>(null)
        private set

    /** Id of the last past broadcast watched, for the same purpose. */
    var lastVideo by mutableStateOf<String?>(null)
        private set

    /** Key of the home card last focused, so the home screen can give it the focus back. Not observed. */
    var homeFocus: String? = null

    /** Name of the last category opened, for the same purpose. */
    var lastCategory by mutableStateOf<String?>(null)
        private set

    private var categoryJob: Job? = null
    private var signInJob: Job? = null
    private var searchJob: Job? = null
    private var channelJob: Job? = null
    private var allFollowed: List<Channel>? = null
    private var allFollowedLoadedAt = 0L

    init {
        restoreSession()
    }

    fun restoreSession() {
        if (store.accessToken == null) {
            signOut()
            return
        }
        session = Session.Loading
        viewModelScope.launch {
            try {
                val user = api.currentUser()
                if (user == null) signOut() else session = Session.SignedIn(user)
            } catch (e: UnauthorizedException) {
                signOut()
            } catch (e: IOException) {
                session = Session.Unreachable
            }
        }
    }

    fun signOut() {
        store.clear()
        playing = null
        closeChannel()
        closeCategory()
        allFollowed = null
        home = HomeFeed.Loading
        categories = CategoryList.Loading
        homeFocus = null
        following = Following.Loading
        searchResults = null
        searchQuery = ""
        tab = Tab.Home
        session = Session.SignedOut
        startSignIn()
    }

    /** Shows a device code and waits for the user to enter it on twitch.tv/activate. */
    fun startSignIn() {
        signInJob?.cancel()
        signInJob = viewModelScope.launch {
            try {
                // A code is valid for 30 minutes: ask for a new one each time it expires.
                while (true) {
                    signIn = SignInState.Requesting
                    val code = api.requestDeviceCode()
                    signIn = SignInState.Code(code.userCode, qrCode("${code.verificationUri}?device-code=${code.userCode}"))
                    val deadline = System.currentTimeMillis() + code.expiresInSec * 1000L
                    while (System.currentTimeMillis() < deadline) {
                        delay(code.intervalSec * 1000L)
                        val tokens = try {
                            api.pollToken(code.deviceCode)
                        } catch (e: IOException) {
                            // An expired or refused code ends this loop at the deadline; a network blip just retries.
                            null
                        }
                        if (tokens != null) {
                            store.save(tokens)
                            restoreSession()
                            return@launch
                        }
                    }
                }
            } catch (e: IOException) {
                signIn = SignInState.Failed("Can't reach Twitch. Check the network connection.")
            }
        }
    }

    /**
     * Reloads the home screen. Twitch refuses its personalised recommendations to this client, so the
     * suggestions are the most watched channels in the content language, overall and in the
     * categories the user watches the most past broadcasts of.
     */
    suspend fun refreshHome() {
        val user = (session as? Session.SignedIn)?.user ?: return
        val language = contentLanguage
        try {
            home = coroutineScope {
                val viewed = async { api.viewedVideos() }
                val followedLive = async { api.followedLive(user.id) }
                val popular = async { api.topStreams(language) }
                val games = (viewed.await() + watch.history())
                    .mapNotNull { it.video.game }
                    .groupingBy { it }.eachCount().entries
                    .sortedByDescending { it.value }
                    .take(2)
                    .map { it.key }
                val perGame = games.map { game -> async { StreamRow("Popular in $game", api.topStreams(language, game)) } }
                val suggestions = listOf(StreamRow(if (language == null) "Popular" else "Popular in $LANGUAGE_NAME", popular.await())) + perGame.awaitAll()
                HomeFeed.Loaded(viewed.await(), followedLive.await(), suggestions.filter { it.channels.isNotEmpty() })
            }
        } catch (e: UnauthorizedException) {
            signOut()
        } catch (e: IOException) {
            // A failed refresh keeps the rows already on screen.
            if (home !is HomeFeed.Loaded) home = HomeFeed.Failed
        }
    }

    fun retryHome() {
        home = HomeFeed.Loading
        viewModelScope.launch { refreshHome() }
    }

    /**
     * Tells Twitch where a past broadcast stopped, so that the user's other devices resume from
     * there. Best effort: the position is kept on this device as well, and outlives the player screen.
     */
    fun reportProgress(video: Video, positionMs: Long) {
        val user = (session as? Session.SignedIn)?.user ?: return
        viewModelScope.launch {
            try {
                api.saveVideoProgress(user.id, video.id, (positionMs / 1000).toInt())
            } catch (e: IOException) {
                Log.w("Twitch", "Could not report the position of video ${video.id}", e)
            }
        }
    }

    /**
     * Past broadcasts started and not finished, most recently watched first. Merges Twitch's list
     * ([viewed]) with this device's own, which also holds what Twitch was not told or not yet asked.
     */
    fun continueWatching(viewed: List<WatchedVideo>): List<WatchedVideo> {
        val byId = LinkedHashMap<String, WatchedVideo>()
        viewed.forEach { byId[it.video.id] = it }
        watch.history().forEach { local ->
            val remote = byId[local.video.id]
            byId[local.video.id] = remote?.copy(updatedAt = maxOf(remote.updatedAt, local.updatedAt)) ?: local
        }
        return byId.values
            .filter { WatchProgress.isInProgress(watch.local(it.video.id), it.video) }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun refreshCategories() {
        try {
            categories = CategoryList.Loaded(api.topCategories())
        } catch (e: UnauthorizedException) {
            signOut()
        } catch (e: IOException) {
            if (categories !is CategoryList.Loaded) categories = CategoryList.Failed
        }
    }

    fun retryCategories() {
        categories = CategoryList.Loading
        viewModelScope.launch { refreshCategories() }
    }

    fun openCategory(category: Category) {
        lastCategory = category.name
        categoryJob?.cancel()
        categoryPage = CategoryPage.Loading(category)
        val language = contentLanguage
        categoryJob = viewModelScope.launch {
            categoryPage = try {
                CategoryPage.Loaded(category, api.topStreams(language, category.name))
            } catch (e: UnauthorizedException) {
                signOut()
                null
            } catch (e: IOException) {
                CategoryPage.Failed(category)
            }
        }
    }

    fun closeCategory() {
        categoryJob?.cancel()
        categoryPage = null
    }

    /** Changes the content language and reloads the category on screen with it. */
    fun selectLanguage(language: String?) {
        if (language == contentLanguage) return
        store.contentLanguage = language
        contentLanguage = language
        categoryPage?.let { openCategory(it.category) }
    }

    fun startDemo() {
        demo = true
        tab = Tab.Following
    }

    /** Random public channels standing in for the user's follows: some shown live, the others as if offline. */
    private suspend fun demoFollowing(): Following.Loaded {
        val channels = api.topStreams(contentLanguage).shuffled()
        return Following.Loaded(
            live = channels.take(DEMO_LIVE_CHANNELS).sortedByDescending { it.stream?.viewers },
            offline = channels.drop(DEMO_LIVE_CHANNELS).map { it.copy(stream = null) }.sortedBy { it.displayName.lowercase() },
        )
    }

    /** Reloads the live channels; the full follow list changes rarely and is reloaded every 10 minutes. */
    suspend fun refreshFollowing() {
        val user = (session as? Session.SignedIn)?.user ?: return
        try {
            if (demo) {
                // Drawn once, so the screen does not reshuffle at each refresh.
                if (following !is Following.Loaded) following = demoFollowing()
                return
            }
            val live = api.followedLive(user.id)
            val now = System.currentTimeMillis()
            val all = allFollowed?.takeIf { now - allFollowedLoadedAt < 600_000 }
                ?: api.followedChannels(user.id).also {
                    allFollowed = it
                    allFollowedLoadedAt = now
                }
            val liveLogins = live.map { it.login }.toSet()
            following = Following.Loaded(live, all.filter { it.login !in liveLogins })
        } catch (e: UnauthorizedException) {
            signOut()
        } catch (e: IOException) {
            // A failed refresh keeps the list already on screen.
            if (following !is Following.Loaded) following = Following.Failed
        }
    }

    fun retryFollowing() {
        following = Following.Loading
        viewModelScope.launch { refreshFollowing() }
    }

    fun openChannel(channel: Channel) {
        lastChannel = channel.login
        lastVideo = null
        channelJob?.cancel()
        channelPage = ChannelPage.Loading(channel)
        channelJob = viewModelScope.launch {
            channelPage = try {
                val (details, videos) = api.channelWithVideos(channel.login) ?: (channel to emptyList())
                ChannelPage.Loaded(details, videos)
            } catch (e: UnauthorizedException) {
                signOut()
                null
            } catch (e: IOException) {
                ChannelPage.Failed(channel)
            }
        }
    }

    fun closeChannel() {
        channelJob?.cancel()
        channelPage = null
    }

    fun search() {
        val query = searchQuery.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        searchResults = ChannelList.Loading
        searchJob = viewModelScope.launch {
            searchResults = try {
                ChannelList.Loaded(api.search(query))
            } catch (e: UnauthorizedException) {
                signOut()
                null
            } catch (e: IOException) {
                ChannelList.Failed
            }
        }
    }

    /** Opens a channel from a list: straight to the stream when it is live, to its page otherwise. */
    fun select(channel: Channel) {
        if (channel.stream != null) playLive(channel) else openChannel(channel)
    }

    fun playLive(channel: Channel) {
        lastChannel = channel.login
        playing = Playable.Live(channel)
    }

    fun playVideo(channel: Channel, video: Video) {
        lastVideo = video.id
        playing = Playable.Vod(channel, video)
    }

    companion object {
        /** The only content language offered besides "all": the code Twitch expects and its name. */
        const val LANGUAGE_CODE = "FR"
        const val LANGUAGE_NAME = "French"
        /** One row of live channels, so that the offline section shows on the same screen. */
        const val DEMO_LIVE_CHANNELS = 4

        /** The account shown in demo mode. */
        val DEMO_USER = User(id = "", login = "username", displayName = "username", avatarUrl = null)
    }

    private fun qrCode(content: String): ImageBitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
        val scale = 8
        val pixels = IntArray(matrix.width * scale * matrix.height * scale)
        val width = matrix.width * scale
        for (y in 0 until matrix.height * scale) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix[x / scale, y / scale]) 0xFF0B0D12.toInt() else 0xFFF3F4F6.toInt()
            }
        }
        return Bitmap.createBitmap(pixels, width, matrix.height * scale, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}
