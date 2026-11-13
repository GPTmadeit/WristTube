package com.wristtube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import coil3.compose.AsyncImage
import com.wristtube.yt.Video

/** Per-row helpers so every row morphs at the screen edge the same way. */
class ListItemScope(val item: TransformingLazyColumnItemScope, val spec: TransformationSpec) {
    val modifier: Modifier get() = Modifier.fillMaxWidth().transformedHeight(item, spec)
    val transformation: SurfaceTransformation get() = with(item) { SurfaceTransformation(spec) }
}

fun TransformingLazyColumnScope.row(
    spec: TransformationSpec,
    key: Any? = null,
    content: @Composable ListItemScope.() -> Unit,
) = item(key = key) { ListItemScope(this, spec).content() }

/** The standard screen: a curved scrolling list, optionally with a button hugging the bottom edge. */
@Composable
fun ListScreen(
    state: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    val spec = rememberTransformationSpec()
    Box(Modifier.background(Color.Black)) {
        if (edgeButton != null) {
            ScreenScaffold(scrollState = state, edgeButton = edgeButton) { padding ->
                TransformingLazyColumn(state = state, contentPadding = padding) { content(spec) }
            }
        } else {
            ScreenScaffold(scrollState = state) { padding ->
                TransformingLazyColumn(state = state, contentPadding = padding) { content(spec) }
            }
        }
    }
}

/** A video in a list: thumbnail with its length, then title and channel. */
@Composable
fun ListItemScope.VideoCard(video: Video, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        transformation = transformation,
        contentPadding = CardDefaults.ContentPadding,
    ) {
        Box {
            AsyncImage(
                model = video.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2A2423)),
            )
            Badge(video, Modifier.align(Alignment.BottomEnd).padding(5.dp))
        }
        Text(
            video.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        val line = listOfNotNull(video.channel.takeIf { it.isNotBlank() }, views(video.views), video.uploaded)
            .joinToString(" · ")
        if (line.isNotBlank()) {
            Text(
                line,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Badge(video: Video, modifier: Modifier) {
    val (text, color) = when {
        video.live -> "LIVE" to Palette.live
        video.durationSec > 0 -> duration(video.durationSec) to Color(0xCC000000)
        else -> return
    }
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(color).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/** A short line of grey text, centred: status, hints, errors. */
@Composable
fun ListItemScope.Caption(text: String, color: Color = Palette.muted) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        textAlign = TextAlign.Center,
    )
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun Spaced(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
fun IconRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

val SmallIcon = 22.dp
val TinyIcon = Modifier.size(18.dp)

fun duration(seconds: Long): String {
    if (seconds < 0) return "LIVE"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun views(n: Long): String? = when {
    n < 0 -> null
    n >= 1_000_000_000 -> "%.1fB views".format(n / 1e9).replace(".0B", "B")
    n >= 1_000_000 -> "%.1fM views".format(n / 1e6).replace(".0M", "M")
    n >= 1_000 -> "%.0fK views".format(n / 1e3)
    n == 1L -> "1 view"
    else -> "$n views"
}
