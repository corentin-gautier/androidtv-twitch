package dev.androidtv.twitch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.androidtv.twitch.data.WatchProgress

/** A channel's page: its past broadcasts, and a way into the stream when it is live. */
@Composable
fun ChannelScreen(vm: AppViewModel, page: ChannelPage) {
    BackHandler(onBack = vm::closeChannel)
    val channel = page.channel
    val stream = channel.stream
    val initialFocus = remember { FocusRequester() }
    val videos = (page as? ChannelPage.Loaded)?.videos.orEmpty()
    // The focus goes to the broadcast last watched, else to the first one, else to "Watch live".
    val target = remember(videos) { (videos.firstOrNull { it.id == vm.lastVideo } ?: videos.firstOrNull())?.id }
    LaunchedEffect(page is ChannelPage.Loaded, target) {
        if (page is ChannelPage.Loaded && (target != null || stream != null)) runCatching { initialFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 42.dp, end = 36.dp, top = 30.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(channel.avatarUrl, 54.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label(channel.displayName, Type.Heading)
                    if (stream != null) LiveBadge()
                }
                Label(stream?.title ?: "Offline · past broadcasts", Type.Button, color = Palette.TextMuted)
            }
            if (stream != null) {
                Pill("Watch live", { vm.playLive(channel) }, if (target == null) Modifier.focusRequester(initialFocus) else Modifier, filled = true)
            }
        }
        when (page) {
            is ChannelPage.Loading -> Box(Modifier.fillMaxSize()) { Spinner(Modifier.align(Alignment.Center)) }
            is ChannelPage.Failed -> Message("Couldn't load this channel's videos.", action = "Retry", onAction = { vm.openChannel(channel) })
            is ChannelPage.Loaded ->
                if (videos.isEmpty()) {
                    Message("${channel.displayName} has no past broadcasts.")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(start = 38.dp, end = 38.dp, top = 8.dp, bottom = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(videos, key = { it.id }) { video ->
                            // Read on each composition so the bar updates after the video is watched.
                            val progress = WatchProgress.fraction(vm.watch.local(video.id), video)
                            VideoCard(
                                video,
                                progress = progress,
                                onClick = { vm.playVideo(channel, video) },
                                modifier = if (video.id == target) Modifier.focusRequester(initialFocus) else Modifier,
                            )
                        }
                    }
                }
        }
    }
}
