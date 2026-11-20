package dev.androidtv.twitch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.androidtv.twitch.data.Category
import kotlinx.coroutines.launch

/** The browse tab: the most watched categories. */
@Composable
fun BrowseTab(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.refreshCategories() }
    when (val state = vm.categories) {
        CategoryList.Loading -> Box(Modifier.fillMaxSize()) { Spinner(Modifier.align(Alignment.Center)) }
        CategoryList.Failed -> Message("Couldn't load the categories.", action = "Retry", onAction = vm::retryCategories)
        is CategoryList.Loaded ->
            if (state.categories.isEmpty()) Message("No category to show.") else CategoryGrid(state.categories, vm.lastCategory, vm::openCategory)
    }
}

/** Grid of categories. Gives the initial focus to [focusName], or to the first one. */
@Composable
private fun CategoryGrid(categories: List<Category>, focusName: String?, onOpen: (Category) -> Unit) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val initialFocus = remember { FocusRequester() }
    val target = remember { (categories.firstOrNull { it.name == focusName } ?: categories.first()).name }
    LaunchedEffect(Unit) {
        val index = categories.indexOfFirst { it.name == target }
        // A target in the first row is already on screen: scrolling to it would hide the heading above it.
        // The heading is the first grid item, hence the shift.
        if (index >= 7) gridState.scrollToItem(index + 1)
        runCatching { initialFocus.requestFocus() }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        state = gridState,
        contentPadding = PaddingValues(start = ScreenPadding - 4.dp, end = ScreenPadding - 4.dp, top = 22.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "#heading", span = { GridItemSpan(maxLineSpan) }) {
            SectionHeading("Categories", "Most watched right now")
        }
        itemsIndexed(categories, key = { _, category -> category.name }) { index, category ->
            CategoryCard(
                category,
                onClick = { onOpen(category) },
                modifier = (if (category.name == target) Modifier.focusRequester(initialFocus) else Modifier)
                    // The first row scrolls back to the very top so the heading stays visible.
                    .onFocusChanged { if (it.hasFocus && index < 7) scope.launch { gridState.animateScrollToItem(0) } },
            )
        }
    }
}

/** A category's page: its live channels, most watched first, in the content language or in all of them. */
@Composable
fun CategoryScreen(vm: AppViewModel, page: CategoryPage) {
    BackHandler(onBack = vm::closeCategory)
    val category = page.category
    val channels = (page as? CategoryPage.Loaded)?.channels.orEmpty()
    val gridFocus = remember { FocusRequester() }
    val languageFocus = remember { FocusRequester() }
    // The focus goes to the first channel once; after a language change it stays on the language buttons.
    var focusPlaced by remember { mutableStateOf(false) }
    val target = remember(channels) { (channels.firstOrNull { it.login == vm.lastChannel } ?: channels.firstOrNull())?.login }
    LaunchedEffect(page is CategoryPage.Loaded) {
        if (page is CategoryPage.Loaded && !focusPlaced) {
            runCatching { (if (target != null) gridFocus else languageFocus).requestFocus() }
            focusPlaced = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 42.dp, end = 36.dp, top = 30.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = category.boxArtUrl,
                contentDescription = null,
                modifier = Modifier.width(54.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(6.dp)).background(Palette.SurfaceHigh),
                contentScale = ContentScale.Crop,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label(category.displayName, Type.Heading)
                Label("${formatViewers(category.viewers)} · live channels, most watched first", Type.Button, color = Palette.TextMuted)
            }
            val language = vm.contentLanguage
            Pill(
                AppViewModel.LANGUAGE_NAME,
                { vm.selectLanguage(AppViewModel.LANGUAGE_CODE) },
                if (language != null) Modifier.focusRequester(languageFocus) else Modifier,
                filled = language != null,
            )
            Pill(
                "All languages",
                { vm.selectLanguage(null) },
                if (language == null) Modifier.focusRequester(languageFocus) else Modifier,
                filled = language == null,
            )
        }
        when (page) {
            is CategoryPage.Loading -> Box(Modifier.fillMaxSize()) { Spinner(Modifier.align(Alignment.Center)) }
            is CategoryPage.Failed -> Message("Couldn't load this category.", action = "Retry", onAction = { vm.openCategory(category) })
            is CategoryPage.Loaded ->
                if (channels.isEmpty()) {
                    Message("No live channel in ${category.displayName} right now.")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(start = 38.dp, end = 38.dp, top = 8.dp, bottom = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(channels, key = { it.login }) { channel ->
                            ChannelCard(
                                channel,
                                onClick = { vm.select(channel) },
                                modifier = if (channel.login == target) Modifier.focusRequester(gridFocus) else Modifier,
                            )
                        }
                    }
                }
        }
    }
}
