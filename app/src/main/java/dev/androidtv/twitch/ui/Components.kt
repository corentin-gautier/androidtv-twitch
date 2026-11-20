package dev.androidtv.twitch.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.androidtv.twitch.data.Category
import dev.androidtv.twitch.data.Channel
import dev.androidtv.twitch.data.Video

@Composable
fun Label(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified, maxLines: Int = 1) {
    BasicText(
        text = text,
        modifier = modifier,
        style = if (color == Color.Unspecified) style else style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The focus indicator shared by every focusable element: a white ring around it. */
fun Modifier.focusRing(focused: Boolean, shape: Shape): Modifier =
    border(2.dp, if (focused) Palette.Text else Color.Transparent, shape).padding(4.dp)

/** Pill-shaped button. [filled] marks the primary or selected one. */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    leading: (@Composable RowScope.(Color) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val content = if (filled) Palette.Background else Palette.TextSecondary
    Row(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .focusRing(focused, CircleShape)
            .clip(CircleShape)
            .background(if (filled) Palette.Text else Palette.SurfaceHigh)
            .defaultMinSize(minHeight = 36.dp)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this, content)
        Label(text, Type.Button, color = content)
    }
}

@Composable
fun Avatar(url: String?, size: Dp, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier.size(size).clip(CircleShape).background(Palette.Placeholder),
        contentScale = ContentScale.Crop,
    )
}

/** A channel's avatar as a button, with the usual focus ring around it. */
@Composable
fun AvatarButton(url: String?, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .focusRing(focused, CircleShape),
    ) {
        Avatar(url, size)
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Label("LIVE", Type.Badge, modifier.clip(RoundedCornerShape(3.dp)).background(Palette.Live).padding(horizontal = 6.dp, vertical = 2.dp))
}

/** Card for one channel: stream preview, then avatar, name, title and category. */
@Composable
fun ChannelCard(channel: Channel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "cardScale")
    val stream = channel.stream
    val thumbnailShape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().focusRing(focused, thumbnailShape)) {
            AsyncImage(
                model = stream?.previewUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceHigh),
                contentScale = ContentScale.Crop,
            )
            if (stream != null) {
                LiveBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
                Label(
                    formatViewers(stream.viewers),
                    Type.Small,
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Palette.Background)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Palette.Text,
                )
            }
        }
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Avatar(channel.avatarUrl, 28.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label(channel.displayName, Type.Label)
                Label(stream?.title ?: "Offline", Type.Small, color = Palette.TextSecondary)
                if (stream?.game != null) Label(stream.game, Type.Small)
            }
        }
    }
}

/** Tile for a followed channel that is offline: its avatar and name. */
@Composable
fun OfflineChannelTile(channel: Channel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.focusRing(focused, CircleShape)) { Avatar(channel.avatarUrl, 84.dp) }
        Label(channel.displayName, Type.Label, color = if (focused) Palette.Text else Palette.TextSecondary)
    }
}

/**
 * Card for a past broadcast: preview with [badge] (its duration by default) and the watched share,
 * then title, date and category. With [channel], the card also names the channel, for lists that
 * mix several of them.
 */
@Composable
fun VideoCard(
    video: Video,
    progress: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    channel: Channel? = null,
    badge: String = formatDuration(video.lengthSeconds.toLong()),
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "cardScale")
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().focusRing(focused, RoundedCornerShape(12.dp))) {
            AsyncImage(
                model = video.previewUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceHigh),
                contentScale = ContentScale.Crop,
            )
            Label(
                badge,
                Type.Small,
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 9.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Palette.Background)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = Palette.Text,
            )
            // How far the broadcast was watched, along the very bottom of the preview.
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Palette.Scrim),
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Palette.Accent))
                }
            }
        }
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (channel != null) Avatar(channel.avatarUrl, 28.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label(video.title, Type.Label)
                Label(if (channel == null) videoDetails(video) else listOf(channel.displayName, videoDetails(video)).joinToString(" · "), Type.Small)
            }
        }
    }
}

/** Card for a category: its box art, name and audience. */
@Composable
fun CategoryCard(category: Category, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "cardScale")
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().focusRing(focused, RoundedCornerShape(11.dp))) {
            AsyncImage(
                model = category.boxArtUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(7.dp)).background(Palette.SurfaceHigh),
                contentScale = ContentScale.Crop,
            )
        }
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label(category.displayName, Type.Label)
            Label(formatViewers(category.viewers), Type.Small)
        }
    }
}

/** "2 days ago · Category". */
fun videoDetails(video: Video): String =
    listOfNotNull(video.publishedAt.takeIf { it > 0 }?.let { formatRelativeDate(it) }, video.game).joinToString(" · ")

/** A pause symbol (two bars) shown over the video while it is paused. */
@Composable
fun PauseGlyph(modifier: Modifier = Modifier) {
    Row(
        modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(Palette.Scrim),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(2) {
            Box(Modifier.size(width = 10.dp, height = 34.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Text))
        }
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val angle by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "spinnerAngle",
    )
    Canvas(modifier.size(size)) {
        drawArc(Palette.Text, startAngle = angle, sweepAngle = 270f, useCenter = false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** A chevron pointing left, for "Back". */
@Composable
fun BackIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(13.dp)) {
        val stroke = 1.8.dp.toPx()
        val tip = Offset(size.width * 0.3f, size.height * 0.5f)
        drawLine(color, Offset(size.width * 0.7f, size.height * 0.12f), tip, stroke, StrokeCap.Round)
        drawLine(color, tip, Offset(size.width * 0.7f, size.height * 0.88f), stroke, StrokeCap.Round)
    }
}

@Composable
fun SearchIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(15.dp)) {
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
        val radius = size.minDimension * 0.34f
        val center = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(color, radius, center, style = stroke)
        drawLine(color, Offset(center.x + radius * 0.75f, center.y + radius * 0.75f), Offset(size.width * 0.92f, size.height * 0.92f), stroke.width, StrokeCap.Round)
    }
}

/** Centered message with an optional action, for loading failures and empty lists. */
@Composable
fun Message(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(text, Type.Body, maxLines = 3)
        if (action != null) Pill(action, onAction, filled = true)
    }
}
