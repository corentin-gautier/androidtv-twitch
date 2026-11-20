package dev.androidtv.twitch.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import dev.androidtv.twitch.data.Playable
import dev.androidtv.twitch.playback.ChatFeed
import dev.androidtv.twitch.playback.PlaybackStatus
import dev.androidtv.twitch.playback.StreamPlayer
import dev.androidtv.twitch.playback.liveChat
import dev.androidtv.twitch.playback.replayChat
import kotlinx.coroutines.delay
import java.io.IOException

private const val OVERLAY_TIMEOUT_MS = 5_000L

/** Position being chosen with the remote and not applied yet, or -1. */
private const val NOT_SCRUBBING = -1L

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: AppViewModel, playable: Playable, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stream = remember(playable) { StreamPlayer(context.applicationContext, vm.resolver, vm.store, vm.watch, scope, playable, vm::reportProgress) }
    DisposableEffect(stream) { onDispose { stream.release() } }
    // Playback stops when the app leaves the screen and restarts when it returns.
    LifecycleStartEffect(stream) {
        stream.start()
        onStopOrDispose { stream.stop() }
    }

    if (!stream.isLive) {
        LaunchedEffect(stream) {
            while (true) {
                delay(10_000)
                stream.saveProgress()
            }
        }
    }

    // Title, category and viewer count change during a broadcast.
    var channel by remember { mutableStateOf(playable.channel) }
    if (stream.isLive) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(60_000)
                try {
                    vm.api.channel(channel.login)?.let { channel = it }
                } catch (e: IOException) {
                    // Keep showing the last known details.
                }
            }
        }
    }

    // The chat connects only while its panel is shown: live chat for a stream, the replay for a past broadcast.
    var chatVisible by remember { mutableStateOf(vm.store.chatVisible) }
    val chat = remember(playable) { ChatFeed() }
    LaunchedEffect(chatVisible, playable) {
        chat.clear()
        if (chatVisible) {
            when (playable) {
                is Playable.Live -> liveChat(vm.api.http, playable.channel.login, chat)
                is Playable.Vod -> replayChat(vm.api, playable.video.id, { stream.player.currentPosition / 1000 }, chat)
            }
        }
    }

    var overlayVisible by remember { mutableStateOf(true) }
    var qualityMenuOpen by remember { mutableStateOf(false) }
    var interactions by remember { mutableIntStateOf(0) }
    var seekBarFocused by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableLongStateOf(NOT_SCRUBBING) }
    var paused by remember { mutableStateOf(false) }
    val screenFocus = remember { FocusRequester() }
    val status = stream.status
    val stopped = status == PlaybackStatus.Offline || status == PlaybackStatus.Failed

    LaunchedEffect(status) { if (status == PlaybackStatus.Ended) onExit() }
    // The overlay hides after a moment without input, except while paused.
    LaunchedEffect(overlayVisible, interactions, paused) {
        if (overlayVisible && !paused) {
            delay(OVERLAY_TIMEOUT_MS)
            overlayVisible = false
            qualityMenuOpen = false
        }
    }
    LaunchedEffect(overlayVisible, stopped) {
        if (!overlayVisible && !stopped) screenFocus.requestFocus()
    }

    fun togglePause() {
        stream.togglePause()
        paused = !stream.player.playWhenReady
    }

    /** Moves the position being chosen; the player only seeks when the key is released. */
    fun scrub(deltaMs: Long) {
        val duration = stream.player.duration
        if (duration == C.TIME_UNSET) return
        val from = if (scrubPosition == NOT_SCRUBBING) stream.player.currentPosition else scrubPosition
        scrubPosition = (from + deltaMs).coerceIn(0, duration)
    }

    fun back() {
        when {
            qualityMenuOpen -> qualityMenuOpen = false
            overlayVisible && !stopped -> overlayVisible = false
            else -> onExit()
        }
    }
    BackHandler(onBack = ::back)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent.keyCode
                if (key == KeyEvent.KEYCODE_BACK) {
                    // Handled here, before Compose uses Back to move the focus from a button up to this
                    // focusable screen, which would swallow the first press.
                    if (event.type == KeyEventType.KeyUp) back()
                    return@onPreviewKeyEvent true
                }
                val seekKey = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    key == KeyEvent.KEYCODE_MEDIA_REWIND || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                // In a past broadcast, left and right seek whenever they are not needed to move between buttons.
                val seeking = !stream.isLive && seekKey && !stopped && (seekBarFocused || !overlayVisible)
                if (event.type == KeyEventType.KeyUp) {
                    // Releasing the key applies the chosen position, wherever the focus went meanwhile.
                    val scrubbing = seekKey && scrubPosition != NOT_SCRUBBING
                    if (scrubbing) {
                        stream.player.seekTo(scrubPosition)
                        scrubPosition = NOT_SCRUBBING
                    }
                    return@onPreviewKeyEvent scrubbing
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                interactions++
                when {
                    key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY || key == KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        togglePause()
                        overlayVisible = true
                        true
                    }
                    seeking -> {
                        val forward = key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                        scrub(seekStepMs(event.nativeKeyEvent.repeatCount) * if (forward) 1 else -1)
                        overlayVisible = true
                        true
                    }
                    !overlayVisible -> {
                        // The first press only brings the overlay back.
                        overlayVisible = true
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(screenFocus)
            .focusable(),
    ) {
        // The video and its overlays; they shrink to make room for the chat.
        val video: @Composable (Modifier) -> Unit = { videoModifier ->
        Box(videoModifier) {
        AndroidView(
            factory = { PlayerView(it).apply { useController = false; keepScreenOn = true; player = stream.player } },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.player = null },
        )

        if (paused && !stopped) PauseGlyph(Modifier.align(Alignment.Center))

        when (status) {
            PlaybackStatus.Loading -> Spinner(Modifier.align(Alignment.Center))
            PlaybackStatus.Offline -> PlayerMessage("${channel.displayName} is offline.", "Back", onExit)
            PlaybackStatus.Failed -> PlayerMessage(if (stream.isLive) "Couldn't play this stream." else "Couldn't play this video.", "Retry", stream::retry)
            PlaybackStatus.Playing, PlaybackStatus.Ended -> Unit
        }

        AnimatedVisibility(overlayVisible && !stopped, Modifier.align(Alignment.TopStart), enter = fadeIn(), exit = fadeOut()) {
            Pill("Back", onExit, Modifier.padding(start = 36.dp, top = 24.dp), leading = { BackIcon(it) })
        }

        AnimatedVisibility(overlayVisible && !stopped, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.End) {
                if (qualityMenuOpen) QualityMenu(stream, onClose = { qualityMenuOpen = false })
                Column(
                    Modifier.fillMaxWidth().background(Palette.Scrim).padding(start = 42.dp, end = 36.dp, top = 22.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val qualityButton = remember { FocusRequester() }
                    val seekBar = remember { FocusRequester() }
                    var menuWasOpen by remember { mutableStateOf(false) }
                    // The focus starts on the main control, and returns to the Quality button when its menu closes.
                    LaunchedEffect(qualityMenuOpen) {
                        if (!qualityMenuOpen) runCatching { (if (stream.isLive || menuWasOpen) qualityButton else seekBar).requestFocus() }
                        menuWasOpen = qualityMenuOpen
                    }
                    val openChannel = {
                        onExit()
                        vm.openChannel(channel)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
                        AvatarButton(channel.avatarUrl, 48.dp, onClick = openChannel)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Label(channel.displayName, Type.Title)
                                if (stream.isLive) LiveBadge()
                            }
                            val (title, details) = when (playable) {
                                is Playable.Live -> channel.stream?.let { it.title to listOfNotNull(it.game, formatViewers(it.viewers)).joinToString(" · ") } ?: ("" to "")
                                is Playable.Vod -> playable.video.title to videoDetails(playable.video)
                            }
                            Label(title, Type.Button.copy(fontWeight = Type.Body.fontWeight), color = Palette.TextSecondary)
                            Label(details, Type.Small)
                        }
                        if (stream.isLive) {
                            Pill("Videos", openChannel)
                        }
                        Pill("Chat · ${if (chatVisible) "On" else "Off"}", {
                            chatVisible = !chatVisible
                            vm.store.chatVisible = chatVisible
                        })
                        val quality = stream.qualities.firstOrNull { it.height == stream.selectedHeight }?.label ?: "Auto"
                        Pill("Quality · $quality", { qualityMenuOpen = !qualityMenuOpen }, Modifier.focusRequester(qualityButton), filled = stream.isLive)
                    }
                    if (!stream.isLive) {
                        SeekBar(stream, scrubPosition, paused, ::togglePause, Modifier.focusRequester(seekBar).onFocusChanged { seekBarFocused = it.isFocused })
                    }
                }
            }
        }
        }
        }
        Row(Modifier.fillMaxSize()) {
            video(Modifier.weight(1f).fillMaxHeight())
            if (chatVisible) ChatPanel(chat)
        }
    }
}

/** The chat beside the video: read-only, newest line at the bottom. Emotes show as their text. */
@Composable
private fun ChatPanel(chat: ChatFeed) {
    val listState = rememberLazyListState()
    LaunchedEffect(chat.lines.size) {
        if (chat.lines.isNotEmpty()) listState.scrollToItem(chat.lines.lastIndex)
    }
    Column(
        Modifier.width(240.dp).fillMaxHeight().background(Palette.Background).padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label("Chat", Type.Title)
        if (chat.lines.isEmpty()) Label("Waiting for messages…", Type.Small)
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.Bottom)) {
            items(chat.lines, key = { it.key }) { line ->
                BasicText(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFF000000 or line.message.color.toLong()), fontWeight = FontWeight.SemiBold)) {
                            append(line.message.author)
                        }
                        append("  ")
                        append(line.message.text)
                    },
                    style = Type.Small.copy(color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp),
                )
            }
        }
    }
}

/** 10 seconds per press; holding the key goes faster the longer it is held. */
private fun seekStepMs(repeatCount: Int): Long = when {
    repeatCount < 5 -> 10_000L
    repeatCount < 20 -> 30_000L
    else -> 60_000L
}

@Composable
private fun PlayerMessage(text: String, action: String, onAction: () -> Unit) {
    val button = remember { FocusRequester() }
    LaunchedEffect(Unit) { button.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(Palette.Scrim),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(text, Type.Body)
        Pill(action, onAction, Modifier.focusRequester(button), filled = true)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun QualityMenu(stream: StreamPlayer, onClose: () -> Unit) {
    val selectedOption = remember { FocusRequester() }
    val selected = stream.qualities.firstOrNull { it.height == stream.selectedHeight }
    LaunchedEffect(Unit) { runCatching { selectedOption.requestFocus() } }
    Column(
        Modifier.padding(end = 36.dp, bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(Palette.Scrim).padding(8.dp).width(IntrinsicSize.Max),
    ) {
        val choose = { height: Int? ->
            stream.selectQuality(height)
            onClose()
        }
        Pill("Auto", { choose(null) }, Modifier.fillMaxWidth().then(if (selected == null) Modifier.focusRequester(selectedOption) else Modifier), filled = selected == null)
        stream.qualities.forEach { quality ->
            val isSelected = quality.height == selected?.height
            Pill(quality.label, { choose(quality.height) }, Modifier.fillMaxWidth().then(if (isSelected) Modifier.focusRequester(selectedOption) else Modifier), filled = isSelected)
        }
    }
}

/** Progress of a past broadcast. Focusable: OK pauses; left and right are handled by the screen. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SeekBar(stream: StreamPlayer, scrubPosition: Long, paused: Boolean, onTogglePause: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            position = stream.player.currentPosition
            duration = stream.player.duration.takeIf { it != C.TIME_UNSET } ?: 0
            delay(500)
        }
    }
    val shown = if (scrubPosition == NOT_SCRUBBING) position else scrubPosition
    val fraction = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(formatDuration(shown / 1000), Type.Button.copy(fontFeatureSettings = "tnum"))
            Box(
                modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTogglePause)
                    .focusRing(focused, CircleShape)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Palette.Track),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Palette.Accent))
            }
            Label(formatDuration(duration / 1000), Type.Button.copy(fontFeatureSettings = "tnum"), color = Palette.TextMuted)
        }
        Label("Left / Right: skip 10 seconds, hold to go faster · OK: ${if (paused) "play" else "pause"}", Type.Small)
    }
}
