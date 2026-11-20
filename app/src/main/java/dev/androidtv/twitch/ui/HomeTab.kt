package dev.androidtv.twitch.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.androidtv.twitch.data.Channel
import dev.androidtv.twitch.data.WatchProgress
import dev.androidtv.twitch.data.WatchedVideo
import kotlinx.coroutines.delay
import kotlin.math.abs

/** One horizontal row of the home screen. [key] also prefixes the keys of its cards. */
private class HomeRow(val key: String, val title: String, val detail: String, val cards: List<HomeCard>)

private sealed interface HomeCard {
    val key: String

    class Vod(override val key: String, val watched: WatchedVideo) : HomeCard
    class Stream(override val key: String, val channel: Channel) : HomeCard
}

private val CardWidth = 206.dp

private val ListTopPadding = 20.dp
private val HeadingSpacing = 8.dp

/** The home tab: past broadcasts to continue, then live channels the user follows, then suggestions. */
@Composable
fun HomeTab(vm: AppViewModel) {
    // Refresh every two minutes while the rows are on screen.
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    LaunchedEffect(resumed) {
        while (resumed) {
            vm.refreshHome()
            delay(120_000)
        }
    }

    when (val state = vm.home) {
        HomeFeed.Loading -> Box(Modifier.fillMaxSize()) { Spinner(Modifier.align(Alignment.Center)) }
        HomeFeed.Failed -> Message("Couldn't load the home screen.", action = "Retry", onAction = vm::retryHome)
        is HomeFeed.Loaded -> {
            val rows = buildList {
                add(HomeRow("continue", "Continue watching", "Started on any device, not finished", vm.continueWatching(state.viewed).map { HomeCard.Vod("continue/${it.video.id}", it) }))
                add(HomeRow("followed", "Live from channels you follow", "", state.followedLive.map { HomeCard.Stream("followed/${it.login}", it) }))
                state.suggestions.forEach { row ->
                    add(HomeRow(row.title, row.title, "", row.channels.map { HomeCard.Stream("${row.title}/${it.login}", it) }))
                }
            }.filter { it.cards.isNotEmpty() }
            if (rows.isEmpty()) Message("Nothing to show yet. Use Browse or Search to find a channel.") else HomeRows(vm, rows)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeRows(vm: AppViewModel, rows: List<HomeRow>) {
    val initialFocus = remember { FocusRequester() }
    // The focus returns to the card last focused, or starts on the first one.
    val target = remember {
        val keys = rows.flatMap { row -> row.cards.map { it.key } }
        vm.homeFocus?.takeIf { it in keys } ?: keys.first()
    }
    val targetRow = remember { rows.indexOfFirst { row -> row.cards.any { it.key == target } } }
    val columnState = rememberLazyListState(initialFirstVisibleItemIndex = targetRow)
    LaunchedEffect(Unit) { runCatching { initialFocus.requestFocus() } }

    // Scrolling to a focused card normally moves just enough to show it, which cuts the row's heading
    // and leaves a slice of the previous row. Vertically, the card is pinned below its heading instead.
    // The pinned position is exactly where the first row's cards sit when the list is at its top,
    // so focusing a card there moves nothing.
    val defaultSpec = LocalBringIntoViewSpec.current
    val spacing = with(LocalDensity.current) { (ListTopPadding + HeadingSpacing).toPx() }
    val headingHeight = remember { intArrayOf(0) }
    val pinBelowHeading = remember(spacing) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val distance = offset - (spacing + headingHeight[0])
                return if (abs(distance) < 2f) 0f else distance
            }
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides pinBelowHeading) {
    LazyColumn(
        state = columnState,
        contentPadding = PaddingValues(top = ListTopPadding, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(38.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> row.key }) { rowIndex, row ->
            val targetCard = if (rowIndex == targetRow) row.cards.indexOfFirst { it.key == target } else 0
            Column(verticalArrangement = Arrangement.spacedBy(HeadingSpacing)) {
                SectionHeading(row.title, row.detail, Modifier.padding(start = ScreenPadding - 4.dp).onSizeChanged { headingHeight[0] = it.height })
                // Horizontally, the rows keep the default scrolling.
                CompositionLocalProvider(LocalBringIntoViewSpec provides defaultSpec) {
                LazyRow(
                    state = rememberLazyListState(initialFirstVisibleItemIndex = targetCard),
                    contentPadding = PaddingValues(horizontal = ScreenPadding - 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(row.cards, key = { it.key }) { card ->
                        val modifier = Modifier
                            .width(CardWidth)
                            .then(if (card.key == target) Modifier.focusRequester(initialFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) vm.homeFocus = card.key }
                        when (card) {
                            is HomeCard.Stream -> ChannelCard(card.channel, onClick = { vm.select(card.channel) }, modifier = modifier)
                            is HomeCard.Vod -> {
                                val video = card.watched.video
                                val local = vm.watch.local(video.id)
                                val remainingSeconds = video.lengthSeconds - WatchProgress.watchedMs(local, video) / 1000
                                VideoCard(
                                    video,
                                    progress = WatchProgress.fraction(local, video),
                                    onClick = { vm.playVideo(card.watched.channel, video) },
                                    modifier = modifier,
                                    channel = card.watched.channel,
                                    badge = "${formatDuration(remainingSeconds)} left",
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
    }
}
