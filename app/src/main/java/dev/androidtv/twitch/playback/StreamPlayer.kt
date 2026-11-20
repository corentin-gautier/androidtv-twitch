package dev.androidtv.twitch.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import dev.androidtv.twitch.data.AuthStore
import dev.androidtv.twitch.data.Playable
import dev.androidtv.twitch.data.Video
import dev.androidtv.twitch.data.WatchProgress
import dev.androidtv.twitch.data.WatchStore
import dev.androidtv.twitch.data.UnauthorizedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

enum class PlaybackStatus { Loading, Playing, Offline, Ended, Failed }

class Quality(val height: Int, val label: String)

/**
 * Plays a live stream or a past broadcast.
 *
 * A live stream is kept ad-free: when the playlist being played starts carrying ads, the player
 * switches to another stream source. A past broadcast resumes where it was last stopped, on this
 * device or another.
 */
@UnstableApi
class StreamPlayer(
    context: Context,
    private val resolver: StreamResolver,
    private val store: AuthStore,
    private val watch: WatchStore,
    private val scope: CoroutineScope,
    private val playable: Playable,
    /** Called with each position saved for a past broadcast, in milliseconds, to pass it on to Twitch. */
    private val onProgress: (Video, Long) -> Unit,
) {
    private val login = playable.channel.login
    private val video = (playable as? Playable.Vod)?.video
    val isLive get() = video == null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dataSourceFactory = DefaultDataSource.Factory(context)

    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    var status by mutableStateOf(PlaybackStatus.Loading)
        private set
    var qualities by mutableStateOf(emptyList<Quality>())
        private set

    /** Selected video height, or null for automatic quality. */
    var selectedHeight by mutableStateOf(store.preferredHeight)
        private set

    private var loadJob: Job? = null
    private var sourceIndex: Int? = null

    /** Identifies the media source in use, so callbacks from a replaced one are ignored. */
    private var generation = 0
    private var switchingSource = false
    private var adSwitchBlockedUntil = 0L
    private var retries = 0

    init {
        player.playWhenReady = true
        applyQuality()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        status = PlaybackStatus.Playing
                        retries = 0
                    }
                    Player.STATE_BUFFERING -> status = PlaybackStatus.Loading
                    // A live playlist only ends when the broadcast stops.
                    Player.STATE_ENDED -> if (isLive) {
                        status = PlaybackStatus.Offline
                    } else {
                        video?.let {
                            watch.save(playable.channel, it, WatchProgress.FINISHED)
                            onProgress(it, it.lengthSeconds * 1000L)
                        }
                        status = PlaybackStatus.Ended
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                } else if (retries < MAX_RETRIES) {
                    saveProgress()
                    retries++
                    start(delayMs = 1_000L * retries)
                } else {
                    status = PlaybackStatus.Failed
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                qualities = tracks.groups
                    .filter { it.type == C.TRACK_TYPE_VIDEO }
                    .flatMap { group -> (0 until group.length).filter(group::isTrackSupported).map(group::getTrackFormat) }
                    .filter { it.height > 0 }
                    .distinctBy { it.height }
                    .sortedByDescending { it.height }
                    .map { Quality(it.height, "${it.height}p" + if (it.frameRate >= 50f) "60" else "") }
            }
        })
    }

    /** Resolves the stream and starts playing: from the live edge, or from the saved position of a past broadcast. */
    fun start(delayMs: Long = 0) {
        loadJob?.cancel()
        status = PlaybackStatus.Loading
        loadJob = scope.launch {
            delay(delayMs)
            try {
                load(if (video == null) resolver.resolve(login) else resolver.resolveVideo(video))
            } catch (e: CancellationException) {
                throw e
            } catch (e: StreamOfflineException) {
                status = PlaybackStatus.Offline
            } catch (e: UnauthorizedException) {
                status = PlaybackStatus.Failed
            } catch (e: IOException) {
                status = PlaybackStatus.Failed
            }
        }
    }

    fun retry() {
        retries = 0
        start()
    }

    fun stop() {
        saveProgress()
        loadJob?.cancel()
        generation++
        player.stop()
    }

    fun release() {
        stop()
        player.release()
    }

    fun togglePause() {
        if (player.playWhenReady) {
            player.playWhenReady = false
        } else {
            // A live stream resumes at the live edge, not where it was paused.
            if (isLive) player.seekToDefaultPosition()
            player.playWhenReady = true
        }
    }

    /**
     * Remembers where a past broadcast is, or that it was watched to the end. Called on stop, and
     * regularly during playback so that a killed app loses a few seconds at most.
     */
    fun saveProgress() {
        val video = video ?: return
        val position = player.currentPosition
        if (position <= 0 || player.duration == C.TIME_UNSET) return
        watch.save(playable.channel, video, if (WatchProgress.isFinished(position, video)) WatchProgress.FINISHED else position)
        onProgress(video, position)
    }

    fun selectQuality(height: Int?) {
        selectedHeight = height
        store.preferredHeight = height
        applyQuality()
    }

    private fun applyQuality() {
        val height = selectedHeight
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            if (height == null) {
                clearVideoSizeConstraints()
                setForceHighestSupportedBitrate(false)
            } else {
                // The best track at or under the chosen height, without adapting down.
                setMaxVideoSize(Int.MAX_VALUE, height)
                setForceHighestSupportedBitrate(true)
            }
        }.build()
    }

    private fun load(stream: ResolvedStream) {
        val current = ++generation
        sourceIndex = stream.sourceIndex
        if (stream.hasAds) blockAdSwitch()
        val uri = "data:${MimeTypes.APPLICATION_M3U8};base64," +
            Base64.encodeToString(stream.masterPlaylist.toByteArray(), Base64.NO_WRAP)
        val item = MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        val source = HlsMediaSource.Factory(dataSourceFactory)
            .setPlaylistParserFactory(
                AdDetectingPlaylistParserFactory { mainHandler.post { if (current == generation) onAdsDetected() } },
            )
            .createMediaSource(item)
        if (video == null) player.setMediaSource(source) else player.setMediaSource(source, WatchProgress.resumeMs(watch.local(video.id), video))
        player.prepare()
    }

    private fun onAdsDetected() {
        if (switchingSource || System.currentTimeMillis() < adSwitchBlockedUntil) return
        switchingSource = true
        val current = generation
        scope.launch {
            try {
                val stream = resolver.resolve(login, skip = sourceIndex)
                if (current != generation) return@launch
                if (stream.hasAds) blockAdSwitch() else load(stream)
            } catch (e: IOException) {
                blockAdSwitch()
            } finally {
                switchingSource = false
            }
        }
    }

    /** Every source has ads: stop looking for a while instead of reloading in a loop. */
    private fun blockAdSwitch() {
        adSwitchBlockedUntil = System.currentTimeMillis() + AD_SWITCH_COOLDOWN_MS
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val AD_SWITCH_COOLDOWN_MS = 45_000L
    }
}
