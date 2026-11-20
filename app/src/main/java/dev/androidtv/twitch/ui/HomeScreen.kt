package dev.androidtv.twitch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.androidtv.twitch.data.Channel
import dev.androidtv.twitch.data.User
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val ScreenPadding = 42.dp

@Composable
fun HomeScreen(vm: AppViewModel, user: User) {
    var confirmSignOut by remember { mutableStateOf(false) }
    // The channel grid takes the focus once it has something to show; until then it sits on the tabs.
    val tabFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { tabFocus.requestFocus() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = ScreenPadding - 6.dp, end = ScreenPadding - 6.dp, top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Search has no button of its own: its field sits in the bar and takes the room left.
            Tab.entries.filter { it != Tab.Search }.forEach { tab ->
                Pill(tab.label, { vm.tab = tab }, tabFocus.takeIf { vm.tab == tab }.asModifier(), filled = vm.tab == tab)
            }
            SearchBar(vm, Modifier.weight(1f).then(tabFocus.takeIf { vm.tab == Tab.Search }.asModifier()))
            ProfileButton(user, onClick = { confirmSignOut = true })
        }
        when (vm.tab) {
            Tab.Home -> HomeTab(vm)
            Tab.Browse -> BrowseTab(vm)
            Tab.Following -> FollowingTab(vm)
            Tab.Search -> SearchTab(vm)
        }
    }

    if (confirmSignOut) {
        SignOutDialog(user, onConfirm = vm::signOut, onDismiss = { confirmSignOut = false })
    }
}

@Composable
private fun FollowingTab(vm: AppViewModel) {
    // Refresh once a minute while the list is on screen.
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    LaunchedEffect(resumed) {
        while (resumed) {
            vm.refreshFollowing()
            delay(60_000)
        }
    }

    when (val state = vm.following) {
        Following.Loading -> Box(Modifier.fillMaxSize()) { Spinner(Modifier.align(Alignment.Center)) }
        Following.Failed -> Message("Couldn't load your followed channels.", action = "Retry", onAction = vm::retryFollowing)
        is Following.Loaded ->
            if (state.live.isEmpty() && state.offline.isEmpty()) {
                Message("You don't follow any channel yet. Use Search to find one.")
            } else {
                FollowingGrid(state, vm.lastChannel, vm::select)
            }
    }
}

/**
 * Live channels as cards, 4 per row, then offline channels as avatars, 6 per row: hence 12 columns.
 * Gives the initial focus to [focusLogin], or to the first channel.
 */
@Composable
private fun FollowingGrid(state: Following.Loaded, focusLogin: String?, onSelect: (Channel) -> Unit) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val initialFocus = remember { FocusRequester() }
    val all = state.live + state.offline
    val target = remember { (all.firstOrNull { it.login == focusLogin } ?: all.first()).login }
    LaunchedEffect(Unit) {
        val index = all.indexOfFirst { it.login == target }
        // A target in the first row is already on screen: scrolling to it would hide the heading above it.
        val firstRow = if (state.live.isEmpty()) index < 6 else index < 4
        // Headers shift the grid indices: one before the live channels, one more before the offline ones.
        if (!firstRow) gridState.scrollToItem(if (index < state.live.size) index + 1 else index + 2)
        runCatching { initialFocus.requestFocus() }
    }
    // Focus scrolls just enough to show the focused card, which would leave the first heading
    // off screen: channels of the first row scroll back to the very top instead.
    fun Modifier.channelFocus(channel: Channel, firstRow: Boolean): Modifier =
        (if (channel.login == target) focusRequester(initialFocus) else this)
            .onFocusChanged { if (it.hasFocus && firstRow) scope.launch { gridState.animateScrollToItem(0) } }

    LazyVerticalGrid(
        columns = GridCells.Fixed(12),
        state = gridState,
        contentPadding = PaddingValues(start = ScreenPadding - 4.dp, end = ScreenPadding - 4.dp, top = 22.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "#live", span = { GridItemSpan(maxLineSpan) }) {
            val count = state.live.size
            SectionHeading("Live now", if (count == 1) "1 followed channel" else "$count followed channels")
        }
        itemsIndexed(state.live, key = { _, channel -> channel.login }, span = { _, _ -> GridItemSpan(3) }) { index, channel ->
            ChannelCard(channel, onClick = { onSelect(channel) }, modifier = Modifier.channelFocus(channel, firstRow = index < 4))
        }
        if (state.offline.isNotEmpty()) {
            item(key = "#offline", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeading("Offline", "Open a channel to watch its past broadcasts", Modifier.padding(top = 10.dp))
            }
            itemsIndexed(state.offline, key = { _, channel -> channel.login }, span = { _, _ -> GridItemSpan(2) }) { index, channel ->
                OfflineChannelTile(channel, onClick = { onSelect(channel) }, modifier = Modifier.channelFocus(channel, firstRow = state.live.isEmpty() && index < 6))
            }
        }
    }
}

@Composable
internal fun SectionHeading(title: String, detail: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(title, Type.Heading, Modifier.alignByBaseline())
        Label(detail, Type.Button, Modifier.alignByBaseline(), color = Palette.TextMuted)
    }
}

/**
 * The search field of the top bar. It behaves as a button until OK is pressed: moving across the
 * bar with the remote then neither opens the keyboard nor gets caught by the text cursor.
 * Submitting shows the results in the Search tab.
 */
@Composable
private fun SearchBar(vm: AppViewModel, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val barFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var fieldWasFocused by remember { mutableStateOf(false) }
    val selected = vm.tab == Tab.Search
    val content = if (selected) Palette.Background else Palette.TextSecondary
    LaunchedEffect(editing) {
        if (editing) {
            fieldFocus.requestFocus()
            keyboard?.show()
        }
    }

    Row(
        modifier
            .focusRequester(barFocus)
            .onFocusChanged { focused = it.hasFocus }
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                // Earlier results come back on screen while the query is edited.
                if (vm.searchResults != null) vm.tab = Tab.Search
                editing = true
            }
            .focusRing(focused, CircleShape)
            .clip(CircleShape)
            .background(if (selected) Palette.Text else Palette.SurfaceHigh)
            .defaultMinSize(minHeight = 36.dp)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchIcon(content)
        BasicTextField(
            value = vm.searchQuery,
            onValueChange = { vm.searchQuery = it },
            modifier = Modifier
                .weight(1f)
                .focusRequester(fieldFocus)
                .focusProperties { canFocus = editing }
                .onFocusChanged {
                    // Editing ends when the focus leaves the text, by Back or by submitting.
                    if (it.isFocused) {
                        fieldWasFocused = true
                    } else if (fieldWasFocused) {
                        fieldWasFocused = false
                        editing = false
                    }
                },
            textStyle = Type.Button.copy(color = content),
            singleLine = true,
            cursorBrush = SolidColor(if (selected) Palette.Background else Palette.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (vm.searchQuery.isNotBlank()) {
                    vm.search()
                    vm.tab = Tab.Search
                }
                keyboard?.hide()
                barFocus.requestFocus()
            }),
            decorationBox = { field ->
                Box {
                    if (vm.searchQuery.isEmpty()) Label("Search channels", Type.Button, color = content)
                    field()
                }
            },
        )
    }
}

@Composable
private fun SearchTab(vm: AppViewModel) {
    when (val state = vm.searchResults) {
        null -> Message("Type a channel name in the search bar.")
        ChannelList.Loading -> Box(Modifier.fillMaxSize()) { Spinner(Modifier.align(Alignment.Center)) }
        ChannelList.Failed -> Message("The search failed.", action = "Retry", onAction = vm::search)
        is ChannelList.Loaded ->
            if (state.channels.isEmpty()) Message("No channel found.") else SearchResults(state.channels, vm::select)
    }
}

/**
 * Search results in two sections: live channels as video cards (a tap watches the stream) and
 * offline channels as avatars (a tap opens their past broadcasts). The section holding Twitch's
 * most relevant result comes first, so an exact match stays at the top whether or not it is live.
 */
@Composable
private fun SearchResults(channels: List<Channel>, onSelect: (Channel) -> Unit) {
    val live = channels.filter { it.stream != null }
    val offline = channels.filter { it.stream == null }
    val initialFocus = remember(channels) { FocusRequester() }
    val target = remember(channels) { channels.first().login }
    LaunchedEffect(target) { runCatching { initialFocus.requestFocus() } }
    fun Modifier.target(channel: Channel) = if (channel.login == target) focusRequester(initialFocus) else this

    LazyVerticalGrid(
        columns = GridCells.Fixed(12),
        contentPadding = PaddingValues(start = ScreenPadding - 4.dp, end = ScreenPadding - 4.dp, top = 22.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Whichever section holds the best match is laid out first.
        val liveFirst = channels.first().stream != null
        val sections = if (liveFirst) listOf(true, false) else listOf(false, true)
        sections.forEachIndexed { index, isLive ->
            val topPadding = Modifier.padding(top = if (index == 0) 0.dp else 10.dp)
            if (isLive && live.isNotEmpty()) {
                item(key = "#live", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeading("Live", if (live.size == 1) "1 channel streaming now" else "${live.size} channels streaming now", topPadding)
                }
                items(live, key = { it.login }, span = { GridItemSpan(3) }) { channel ->
                    ChannelCard(channel, onClick = { onSelect(channel) }, modifier = Modifier.target(channel))
                }
            } else if (!isLive && offline.isNotEmpty()) {
                item(key = "#channels", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeading("Channels", "Open a channel to watch its past broadcasts", topPadding)
                }
                items(offline, key = { it.login }, span = { GridItemSpan(2) }) { channel ->
                    OfflineChannelTile(channel, onClick = { onSelect(channel) }, modifier = Modifier.target(channel))
                }
            }
        }
    }
}

@Composable
private fun ProfileButton(user: User, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .focusRing(focused, CircleShape)
            .padding(start = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(user.displayName, Type.Button, color = Palette.TextSecondary)
        Avatar(user.avatarUrl, 30.dp)
    }
}

@Composable
private fun SignOutDialog(user: User, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancel = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { cancel.requestFocus() }
    Box(Modifier.fillMaxSize().background(Palette.Scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Surface).padding(30.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Label("Sign out of ${user.displayName}?", Type.Title)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Cancel", onDismiss, Modifier.focusRequester(cancel), filled = true)
                Pill("Sign out", onConfirm)
            }
        }
    }
}

private fun FocusRequester?.asModifier(): Modifier = if (this == null) Modifier else Modifier.focusRequester(this)
